package com.scraper.worker.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * The actual content of the OutboxEvent payload (sent via debezium - the CDC connector).
 */
data class KeywordJobPayload(
    val searchId: Long,
    val userId: String,
    val keyword: String
)
