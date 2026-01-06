package com.scraper.worker.domain.search

import jakarta.persistence.*
import org.hibernate.annotations.JdbcType
import org.hibernate.dialect.PostgreSQLEnumJdbcType
import java.time.ZonedDateTime
import java.time.ZoneOffset

enum class SearchStatus {
    PENDING, PROCESSING, COMPLETED, FAILED
}

@Entity
@Table(name = "keyword_search")
data class KeywordSearch(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "app_user_id", nullable = false, updatable = false)
    val userId: String? = null,

    @Column(nullable = false, updatable = false)
    val keyword: String,

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType::class) // This tells Hibernate 6 to handle the PG casting
    @Column(nullable = false)
    val status: SearchStatus = SearchStatus.PENDING,

    @Column(name = "total_links")
    val totalLinks: Int? = null,

    @Column(name = "total_ads")
    val totalAds: Int? = null,

    @Column(name = "full_html", columnDefinition = "TEXT")
    val fullHtml: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),

    @Column(name = "completed_at")
    val completedAt: ZonedDateTime? = null
)
