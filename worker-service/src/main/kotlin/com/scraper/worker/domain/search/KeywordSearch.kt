package com.scraper.worker.domain.search

import jakarta.persistence.*
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", insertable = false, updatable = false)
    val userId: String? = null,

    @Column(nullable = false)
    val keyword: String,

    @Enumerated(EnumType.STRING)
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
