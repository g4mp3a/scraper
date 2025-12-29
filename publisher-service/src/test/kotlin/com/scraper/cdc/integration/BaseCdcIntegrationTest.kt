package com.scraper.cdc.integration

import org.junit.jupiter.api.BeforeAll
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@Testcontainers
@Import(TestConfig::class)
abstract class BaseCdcIntegrationTest {

    companion object {
        val postgres = PostgreSQLContainer<Nothing>("postgres:15-alpine").apply {
            withCommand("postgres", "-c", "wal_level=logical")
            start()
        }

        val pubsub: GenericContainer<*> = GenericContainer(DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"))
            .withExposedPorts(8085)
            .withCommand("/bin/sh", "-c", "gcloud beta emulators pubsub start --host-port=0.0.0.0:8085")
            .apply { start() }

//        @JvmStatic
//        @BeforeAll
//        fun globalSetup() {
//            // Since @Autowired JdbcTemplate isn't available in static context,
//            // we use a direct JDBC connection to the container for the one-time setup.
//            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
//                conn.createStatement().use { stmt ->
//                    // 1. Create Table
//                    stmt.execute("""
//                        CREATE TABLE IF NOT EXISTS outbox_event (
//                            id UUID PRIMARY KEY,
//                            aggregate_id TEXT NOT NULL,
//                            type TEXT NOT NULL,
//                            payload TEXT NOT NULL,
//                            created_at TIMESTAMP NOT NULL
//                        )
//                    """.trimIndent())
//
//                    // 2. Setup Publication
//                    stmt.execute("""
//                        DO $$
//                        BEGIN
//                            IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'debezium_outbox_pub') THEN
//                                CREATE PUBLICATION debezium_outbox_pub FOR TABLE outbox_event;
//                            END IF;
//                        END $$;
//                    """.trimIndent())
//                }
//            }
//        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val GCP_PROJECT_ID = "test-project"
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            val emulatorHost = "${pubsub.host}:${pubsub.getMappedPort(8085)}"
            registry.add("spring.cloud.gcp.pubsub.emulator-host") { emulatorHost }
            registry.add("spring.cloud.gcp.project-id") { GCP_PROJECT_ID }
        }
    }
}
