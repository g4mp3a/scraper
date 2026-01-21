package com.scraper.worker.dto

/**
 * The actual content of the OutboxEvent payload (sent via debezium - the CDC connector).
 */
data class KeywordJobPayload(
    val searchId: Long,
    val userId: String,
    val keyword: String
)
