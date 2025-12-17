package com.scraper.api.web.dto

data class KeywordUploadResponse(
    val message: String,
    // List of IDs for the newly created keyword search jobs
    val newJobIds: List<Long>,
    // Hardcoding for now. TODO: Revisit when time allows.
    val status: String = "ACCEPTED"
)
