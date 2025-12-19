package com.scraper.worker.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * Represents the structure of the message pushed by GCP Pub/Sub to the webhook.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PubSubMessage(
    // The PubSub message envelope
    val message: Message?,
    val subscription: String? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Message(
        // The base64 encoded payload (OutboxEvent data)
        val data: String?,
        val messageId: String? = null,
        val publishTime: String? = null
    )
}
