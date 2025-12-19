package com.scraper.worker.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.scraper.worker.dto.KeywordJobPayload
import com.scraper.worker.dto.PubSubMessage
import com.scraper.worker.service.ScrapingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.Spy
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus

@ExtendWith(MockitoExtension::class)
class PubSubControllerTest {

    @Spy
    private var objectMapper: ObjectMapper = ObjectMapper()

    @Mock
    private lateinit var scrapingService: ScrapingService

    @InjectMocks
    private lateinit var controller: PubSubController

    @Test
    fun `handlePubSubMessage should return 400 when base64 data is invalid`() {
        // Arrange
        val malformedMessage = PubSubMessage(
            message = PubSubMessage.Message(
                data = "!!!", // Triggers IllegalArgumentException in Decoder
                messageId = "123",
                publishTime = "2025-01-01"
            )
        )

        // Act
        val response = controller.handlePubSubMessage(malformedMessage)

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)

        val body = response.body ?: ""
        assertTrue(body.contains("Invalid message format"), "Expected error message not found in: $body")

        // Verify we didn't waste cycles calling the service
        verify(scrapingService, never()).processJob(any(KeywordJobPayload::class.java))
    }
}
