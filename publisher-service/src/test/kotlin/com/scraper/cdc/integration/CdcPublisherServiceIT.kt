package com.scraper.cdc.service

import com.scraper.cdc.integration.BaseCdcIntegrationTest
import com.google.cloud.pubsub.v1.Publisher
import com.google.pubsub.v1.PubsubMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.awaitility.Awaitility.await
import java.time.Duration
import java.util.*

@SpringBootTest
@ActiveProfiles("test")
class CdcPublisherServiceIT : BaseCdcIntegrationTest() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var mockPublisher: Publisher

    @Autowired
    private lateinit var cdcPublisherService: CdcPublisherService

    @Test
    fun `should capture insert on outbox table and publish to pubsub`() {
        // 1. Manually trigger the service run
        // Using `@ConditionalOnProperty`, the publisher service's run method wont fire post context load.
        cdcPublisherService.start()

        // 2. Wait for Debezium to initialize the slot in the DB
        waitForDebezium()

        // 3. Act: Insert data
        val payload = """{"search": "it works!"}"""
        jdbcTemplate.update(
            """INSERT INTO outbox_event (aggregate_type, aggregate_id, type, payload, created_at) 
                VALUES ('SCRAPER', ?, 'KEYWORD_UPLOADED', ?::jsonb, NOW())""",
            "job-101",
            payload
        )

        // 6. Assert: Verify the mock publisher received the data
        val messageCaptor = ArgumentCaptor.forClass(com.google.pubsub.v1.PubsubMessage::class.java)
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            verify(mockPublisher, atLeastOnce()).publish(messageCaptor.capture())
            assertEquals(payload, messageCaptor.value.data.toStringUtf8())
        }
    }

    private fun waitForDebezium() {
        await()
            .atMost(Duration.ofSeconds(7))
            .until {
                val count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_replication_slots WHERE slot_name = 'debezium_slot_outbox'",
                    Int::class.java
                )
                count != null && count > 0
            }
    }
}
