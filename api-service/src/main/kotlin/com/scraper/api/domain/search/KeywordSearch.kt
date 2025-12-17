package com.scraper.api.domain.search

import jakarta.persistence.*
import com.scraper.api.domain.user.AppUser
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
    @JoinColumn(name = "app_user_id", referencedColumnName = "firebase_uid", nullable = false)
    val user: AppUser,

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
