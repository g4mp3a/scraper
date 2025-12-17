package com.scraper.api.domain.outbox

import org.springframework.data.jpa.repository.JpaRepository

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    // This repository will primarily handle 'save' operations within the API Service.
    // The publisher service (CDC event handler) will handle reading and updating 'processed_at'.
}