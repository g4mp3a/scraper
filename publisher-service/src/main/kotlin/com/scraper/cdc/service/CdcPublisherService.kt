package com.scraper.cdc.service

import io.debezium.engine.DebeziumEngine
import io.debezium.engine.ChangeEvent
import io.debezium.engine.format.Json
import io.debezium.config.Configuration
import com.google.cloud.pubsub.v1.Publisher
import com.google.protobuf.ByteString
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.Gson
import com.google.gson.JsonObject
import jakarta.annotation.PreDestroy

@Component
open class CdcPublisherService(
    @Value("\${gcp.pubsub.scraper-topic-id}") private val scraperTopicId: String,
    private val env: Environment,
    private val objectMapper: ObjectMapper // Use the Spring-managed ObjectMapper
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var engine: DebeziumEngine<ChangeEvent<String, String>>
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var publisher: Publisher

    // Debezium configuration constants
    private val TARGET_TABLE = "public.outbox_event"
    private val TOPIC_PREFIX = "scraper-db-server"
    // TODO: Slot name configured in cdc_setup.sql and used here should match
    private val REPLICATION_SLOT_NAME = "debezium_slot_outbox"

    override fun run(vararg args: String?) {
        try {
            // 1. Initialize Pub/Sub Publisher
            val topicName = "projects/${System.getenv("GCP_PROJECT_ID")}/topics/$scraperTopicId"
            publisher = createPublisher(topicName)
            log.info("CDC Publisher initialized for topic: $topicName")

            // 2. Build Debezium Configuration
            val dbConfig = createDebeziumConfig()

            // 3. Create the Debezium Engine
            engine = DebeziumEngine.create(Json::class.java)
                .using(dbConfig.asProperties())
                .notifying { record: ChangeEvent<String, String> ->
                    handleChangeEvent(record)
                }
                .build()

            // 4. Submit the engine to run asynchronously
            executor.execute(engine)
            log.info("Debezium Engine started, streaming changes from: $TARGET_TABLE")

        } catch (e: Exception) {
            log.error("Failed to start Debezium CDC Publisher: ${e.message}", e)
            executor.shutdown()
            throw RuntimeException("Failed to start CDC streaming.", e)
        }
    }

    // Factory method to allow Spying in tests
    open fun createPublisher(topicName: String): Publisher {
        return Publisher.newBuilder(topicName).build()
    }

    private fun handleChangeEvent(changeEvent: ChangeEvent<String, String>) {
        // The 'value' contains the full change event from the DB.
        val jsonPayload = changeEvent.value()
        if (changeEvent.destination().endsWith(TARGET_TABLE) && jsonPayload != null) {
            try {
                // Parse the Debezium JSON structure to extract the specific 'payload' column from the database row.
                // The structure is typically: { "before": null, "after": { "id": 1, "payload": "{...}" }, "source": {...} }
                val gson = Gson()
                val debeziumJson = gson.fromJson(jsonPayload, JsonObject::class.java)

                // Debezium sends events for schema changes, heartbeats, and data changes.
                // We only care about INSERTs on the outbox_event table.
                // Check Debezium 'op' field: 'c' stands for Create (Insert)
                val op = debeziumJson.get("op")?.asString
                if (op != "c") return

                // Extract the value of the 'payload' column from the 'after' state.
                val after = debeziumJson.getAsJsonObject("after")
                val eventPayloadJsonString = after.getAsJsonPrimitive("payload")?.asString
                    ?: throw IllegalStateException("Outbox event is missing 'payload' column.")

                // A. Publish the extracted payload to Pub/Sub
                val message = com.google.pubsub.v1.PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8(eventPayloadJsonString)) // Send only the original API service payload
                    .putAttributes("aggregate-id", after.getAsJsonPrimitive("aggregate_id").asString)
                    .putAttributes("event-type", after.getAsJsonPrimitive("type").asString)
                    .build()

                // Publish synchronously to ensure backpressure and reliable delivery tracking
                publisher.publish(message).get()
                log.debug("Published event ID: ${after.getAsJsonPrimitive("id").asLong}")

            } catch (e: Exception) {
                log.error("Failed to parse or publish event to Pub/Sub. Stopping engine to prevent message loss: ${e.message}", e)
                // Critical: Stop the engine. Debezium will resume from the last known offset on restart.
                engine.close()
            }
        }
    }

    private fun createDebeziumConfig(): Configuration {
        // Helper function to extract DB connection properties from Spring configuration
        val dbUrl = env.getProperty("spring.datasource.url", "")
        val dbHost = dbUrl.substringAfter("://").substringBefore(":")
        val dbPort = dbUrl.substringAfterLast(":").substringBefore("/")
        val dbName = dbUrl.substringAfterLast("/")

        return Configuration.create()
            // General Settings
            .with("connector.class", "io.debezium.connector.postgresql.PostgresConnector")
            .with("name", "outbox-connector")

            // Offset Storage (Required for state management in Cloud Run)
            .with("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore")
            // TODO: Ensure that this is set up as a writable path in Cloud Run
            .with("offset.storage.file.filename", "/tmp/offsets.dat")
            .with("offset.flush.interval.ms", 5000)

            // Database Connection Settings
            .with("database.hostname", dbHost)
            .with("database.port", dbPort)
            .with("database.user", env.getProperty("spring.datasource.username"))
            .with("database.password", env.getProperty("spring.datasource.password"))
            .with("database.dbname", dbName)
            .with("database.server.name", TOPIC_PREFIX)

            // PostgreSQL-Specific Settings
            .with("plugin.name", "pgoutput")
            .with("snapshot.mode", "initial")
            .with("publication.name", "debezium_outbox_pub") // Must match the Liquibase created publication
            .with("publication.autocreate.mode", "disabled") // Publication is created by Liquibase
            .with("slot.name", REPLICATION_SLOT_NAME) // Slot will be created/managed by Debezium
            .with("slot.drop.on.stop", "false") // Do not drop the slot on exit!

            // Filtering: Only monitor the outbox_event table
            .with("table.include.list", TARGET_TABLE)
            .build()
    }

    @PreDestroy
    fun shutdown() {
        log.info("Debezium Engine shutting down...")
        try {
            engine.close()
            publisher.shutdown()
            executor.shutdownNow()
        } catch (e: Exception) {
            log.error("Error during Debezium shutdown: ${e.message}", e)
        }
    }
}
