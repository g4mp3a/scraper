package com.scraper.worker.integration

import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration

@Testcontainers
abstract class BaseWorkerIntegrationTest {

    companion object {
        const val PROJECT_ID = "test-project"
        const val TOPIC_ID = "scraper-topic"

        // 1. Database Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15-alpine").apply {
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanDatabase() {
        // Clear both tables to ensure isolation between test runs
        jdbcTemplate.execute("TRUNCATE TABLE keyword_search RESTART IDENTITY CASCADE")
    }

    /**
     * Pragmatic helper to wait for the Async worker to finish its business in the DB
     */
    fun awaitStatusUpdate(jobId: Long, expectedStatus: String) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until {
                val status = jdbcTemplate.queryForObject(
                    "SELECT status FROM keyword_search WHERE id = ?",
                    String::class.java,
                    jobId
                )
                status == expectedStatus
            }
    }
}
