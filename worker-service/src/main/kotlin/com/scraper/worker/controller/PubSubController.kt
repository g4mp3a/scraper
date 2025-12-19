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
    fun handlePubSubMessage(@RequestBody envelope: PubSubMessage?): ResponseEntity<String> {
        try {
            // 1. Guard against null envelope or data
            val data = envelope?.message?.data
            if (data == null) {
                logger.error("Received malformed Pub/Sub message with empty or missing data field")
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing message data")
            }

            // 2. Decoding & Mapping
            val payload = try {
                val decodedData = Base64.getDecoder().decode(data).toString(Charsets.UTF_8)
                objectMapper.readValue(decodedData, KeywordJobPayload::class.java)
            } catch (e: Exception) {
                logger.error("Failed to decode/parse Pub/Sub payload: ${e.message}")
                // Return 400: Pub/Sub will NOT retry this poison message
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid message format: ${e.message}")
            }

            logger.info("Offloading job message for searchId: ${payload.searchId} to @Async pool.")

            // 3. Delegate to Service (This is @Async, so it returns immediately)
            scrapingService.processJob(payload)

            return ResponseEntity.ok("Message received and processing offloaded.")
        } catch (e: Exception) {
            // This catches unexpected system errors (e.g., ThreadPool exhaustion)
            logger.error("System error during Pub/Sub offload: ${e.message}", e)
            // Return 500: Pub/Sub WILL retry this as it might be a transient system issue
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal processing failed. Retry requested.")
        }
    }
}
