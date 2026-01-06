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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Autowired
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
    private val objectMapper: ObjectMapper, // Use the Spring-managed ObjectMapper
    @Autowired(required = false) private var injectedPublisher: Publisher? = null,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var engine: DebeziumEngine<ChangeEvent<String, String>>
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var publisher: Publisher

    // Debezium configuration constants
    private val DB_SERVER_NAME = "scraper-db-server"
    private val TARGET_TABLE = "public.outbox_event"

    // TODO: Slot name configured in cdc_setup.sql and used here should match
    private val REPLICATION_SLOT_NAME = "debezium_slot_outbox"

    override fun run(vararg args: String?) {
        // Simple "Skip" logic for tests
        val isEnabled = env.getProperty("cdc.publisher.enabled", Boolean::class.java, true)
        if (!isEnabled) {
            log.info("CDC Publisher is disabled via properties. Skipping auto-start.")
            return
        }

        start(*args)
    }

    fun start(vararg args: String?) {
        try {
            // 1. Initialize Pub/Sub Publisher
            val projectId = env.getProperty("spring.cloud.gcp.project-id")
                ?: throw IllegalStateException("spring.cloud.gcp.project-id must be set")
            val topicName = "projects/$projectId/topics/$scraperTopicId"

            this.publisher = injectedPublisher ?: createPublisher(topicName)
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
        log.debug("DEBUG: Event value: ${changeEvent.value()}")
        log.debug("DEBUG: Received event for destination: ${changeEvent.destination()}")

        val jsonPayload = changeEvent.value()
        val destination = changeEvent.destination()

        // 1. Validate destination and payload existence
        jsonPayload?.takeIf { destination.endsWith(TARGET_TABLE) } ?: run {
            log.trace("Discarding noise: Event for $destination is not our target.")
            return
        }

        try {
            val root = Gson().fromJson(jsonPayload, JsonObject::class.java)

            // 2. Validate 'op' (Insert only)
            val op = root.get("op")?.asString
            if (op != "c") {
                log.debug("Skipping non-insert ($op) for $destination")
                return
            }

            // 3. Validate 'after' block
            val after = root.getAsJsonObject("after") ?: run {
                log.error("Malformed CDC event: 'after' block missing for $destination")
                return
            }

            // 4. Validate and Extract Payload
            val eventPayload = after.get("payload")?.asString ?: run {
                log.error("Critical: 'payload' column missing in DB event for $destination")
                return
            }

            val message = com.google.pubsub.v1.PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(eventPayload))
                .putAttributes("aggregate-id", after.get("aggregate_id")?.asString ?: "unknown")
                .putAttributes("event-type", after.get("type")?.asString ?: "unknown")
                .build()

            publisher.publish(message).get()
            log.info("Published CDC Event: ID=${after.get("id")}")

        } catch (e: Exception) {
            log.error("Failed to parse or publish event to Pub/Sub. Stopping engine to prevent message loss: ${e.message}", e)
            /*
             TODO: Revisit if needed in a future version.
             For the beta version, lets rely on user retying their search attempts.
             In a future version, we can implement the simple strategy of writing the failed message entry again to outbox_event table
             in case of data issues (specifically messages parsing issues related to debezium)
             and not code/logic issues. Code issues would be logged as critical failures only.
             If this idea of writing to the outbox_event table directly doesnt feel good,
             we can write the entry back to the keyword_search table instead.
             */

        }
    }

    private fun createDebeziumConfig(): Configuration {
        // Helper function to extract DB connection properties from Spring configuration

        val dbUrl = env.getProperty("spring.datasource.url", "")

        val afterScheme = dbUrl.substringAfter("jdbc:postgresql://")
        val hostPort = afterScheme.substringBefore("/")
        val dbHost = hostPort.substringBefore(":")
        val dbPort = hostPort.substringAfter(":", "5432")
        val dbName = afterScheme.substringAfter("/").substringBefore("?")

        var configBuilder = Configuration.create()
            // General Settings
            .with("connector.class", "io.debezium.connector.postgresql.PostgresConnector")
            .with("name", "outbox-connector")
            .with("schemas.enable", "false")  // Removes the "schema" block
            .with("converter.schemas.enable", "false") // Ensures the converter respects it

            // Offset Storage (Required for state management in Cloud Run)
//            .with("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore")
            // TODO: Ensure that this is set up as a writable path in Cloud Run
//            .with("offset.storage.file.filename", "/tmp/offsets.dat")
//            .with("offset.flush.interval.ms", 5000)

            // Database Connection Settings
            .with("database.hostname", dbHost)
            .with("database.port", dbPort)
            .with("database.user", env.getProperty("spring.datasource.username"))
            .with("database.password", env.getProperty("spring.datasource.password"))
            .with("database.dbname", dbName)
            .with("topic.prefix", DB_SERVER_NAME)

            // PostgreSQL-Specific Settings
            .with("plugin.name", "pgoutput")
            .with("snapshot.mode", "initial")
            .with("publication.name", "debezium_outbox_pub") // Must match the Liquibase created publication
            .with("publication.autocreate.mode", "disabled") // Publication is created by Liquibase
            .with("slot.name", REPLICATION_SLOT_NAME) // Slot will be created/managed by Debezium
            .with("slot.drop.on.stop", "false") // Do not drop the slot on exit!

            // Filtering: Only monitor the outbox_event table
            .with("table.include.list", TARGET_TABLE)

        configBuilder = if (env.activeProfiles.contains("test")) {
                    configBuilder
                        .with("offset.storage", "org.apache.kafka.connect.storage.MemoryOffsetBackingStore")
                } else {
                    configBuilder
                        .with("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore")
                        // TODO: This must be set up as a writable path in Cloud Run
                        .with("offset.storage.file.filename", "/tmp/offsets.dat")
                        .with("offset.flush.interval.ms", "5000")
                }

        return configBuilder.build()
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
