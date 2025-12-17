package com.scraper.api.web.dto

import com.scraper.api.domain.search.KeywordSearch

data class KeywordDetailsDTO(
    val id: Long,
    val keyword: String,
    val status: String,
    val createdAt: java.time.ZonedDateTime,
    val totalLinks: Int?,
    val totalAds: Int?,
    // Includes the full HTML content
    val fullHtml: String?
) {
    companion object {
        fun fromEntity(entity: KeywordSearch): KeywordDetailsDTO {
            return KeywordDetailsDTO(
                id = entity.id,
                keyword = entity.keyword,
                status = entity.status.name,
                createdAt = entity.createdAt,
                totalLinks = entity.totalLinks,
                totalAds = entity.totalAds,
                fullHtml = entity.fullHtml
            )
        }
    }
}
