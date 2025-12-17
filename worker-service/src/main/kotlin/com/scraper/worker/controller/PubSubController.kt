package com.scraper.worker.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.scraper.worker.dto.KeywordJobPayload
import com.scraper.worker.dto.PubSubMessage
import com.scraper.worker.service.ScrapingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.Base64
import org.slf4j.LoggerFactory

@RestController
class PubSubController(
    private val objectMapper: ObjectMapper,
    private val scrapingService: ScrapingService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/pubsub/push")
    fun handlePubSubMessage(@RequestBody message: PubSubMessage): ResponseEntity<String> {
        try {
            val decodedData = Base64.getDecoder().decode(message.message.data).toString(Charsets.UTF_8)
            val payload = objectMapper.readValue(decodedData, KeywordJobPayload::class.java)

            logger.info("Offloading job message for searchId: ${payload.searchId} to @Async pool.")

            // Delegate the long-running task to the @Async thread pool
            // The main thread continues and returns 200 OK immediately.
            scrapingService.processJob(payload)

            // Acknowledge message receipt/offload immediately by returning a 200 Ok response
            return ResponseEntity.ok("Message received and processing offloaded.")

        } catch (e: Exception) {
            // Return 500 only if decoding/offloading fails immediately.
            logger.error("Failed to decode or offload Pub/Sub message: ${e.message}", e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Immediate processing failed. Please retry.")
        }
    }
}
