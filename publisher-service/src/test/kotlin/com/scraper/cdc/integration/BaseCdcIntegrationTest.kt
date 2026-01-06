package com.scraper.cdc.integration

import com.google.api.gax.core.CredentialsProvider
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.auth.Credentials
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.TopicAdminSettings
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.PushConfig
import com.google.pubsub.v1.TopicName
import io.grpc.ManagedChannelBuilder
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration

@Testcontainers
abstract class BaseCdcIntegrationTest {

    companion object {
        const val PROJECT_ID = "test-project"
        const val TOPIC_ID = "scraper-topic"
        const val SUB_ID = "scraper-sub"

        val postgres = PostgreSQLContainer<Nothing>("postgres:15-alpine").apply {
            withCommand("postgres", "-c", "wal_level=logical")
            start()
        }

        val pubsub: GenericContainer<*> = GenericContainer(DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"))
            .withExposedPorts(8085)
            .withCommand("/bin/sh", "-c", "gcloud beta emulators pubsub start --host-port=0.0.0.0:8085")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            val emulatorHost = "${pubsub.host}:${pubsub.getMappedPort(8085)}"
            registry.add("spring.cloud.gcp.pubsub.emulator-host") { emulatorHost }
            registry.add("spring.cloud.gcp.project-id") { PROJECT_ID }
            registry.add("gcp.pubsub.scraper-topic-id") { TOPIC_ID }
        }
    }

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    fun waitForDebezium() {
        await()
            .atMost(Duration.ofSeconds(7))
            .until {
                val count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_replication_slots WHERE slot_name = 'debezium_slot_outbox'",
                    Int::class.java
                )
                count != null && count > 0
            }

        await().pollDelay(Duration.ofMillis(500)).until { true }
    }

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_event")
    }
}