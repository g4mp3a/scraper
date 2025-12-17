package com.scraper.api.web.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SignupRequest(
    // The unique Firebase ID (uid) sent by the client after successful Firebase registration
    @field:NotBlank
    @field:Size(min = 10, max = 128) // Typical Firebase UID length
    val firebaseUid: String,

    @field:NotBlank
    @field:Email
    val email: String
)
