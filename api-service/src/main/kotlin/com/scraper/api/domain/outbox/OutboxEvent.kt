package com.scraper.api.domain.outbox

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.*
import java.time.ZonedDateTime
import java.time.ZoneOffset

@Entity
@Table(name = "outbox_event")
data class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // The type of the domain entity that generated the event (e.g., "KEYWORD_SEARCH")
    @Column(name = "aggregate_type", nullable = false, length = 100)
    val aggregateType: String,

    // The ID of the specific domain entity (e.g., the keyword_search.id)
    @Column(name = "aggregate_id", nullable = false, length = 128)
    val aggregateId: String,

    // The specific event type (e.g., "KEYWORD_UPLOADED")
    @Column(name = "type", nullable = false, length = 100)
    val type: String,

    // The data payload for the message queue
    @Column(name = "payload", columnDefinition = "JSONB", nullable = false)
    val payload: JsonNode,

    // When the event was created (used for ordering and CDC)
    @Column(name = "created_at", nullable = false)
    val createdAt: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),

    // When the CDC connector successfully published the event to Pub/Sub
    @Column(name = "processed_at")
    val processedAt: ZonedDateTime? = null
)
