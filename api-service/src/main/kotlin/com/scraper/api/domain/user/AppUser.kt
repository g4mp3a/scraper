package com.scraper.api.domain.user

import jakarta.persistence.*

@Entity
@Table(name = "app_user")
data class AppUser(
    // The Firebase UID serves as the primary key
    @Id
    @Column(name = "firebase_uid", length = 128)
    val firebaseUid: String,

    @Column(name = "email", unique = true, nullable = false)
    val email: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: java.time.ZonedDateTime = java.time.ZonedDateTime.now()
)
