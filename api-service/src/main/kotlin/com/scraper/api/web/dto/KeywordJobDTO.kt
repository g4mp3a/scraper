package com.scraper.api.web.dto

import com.scraper.api.domain.search.KeywordSearch

data class KeywordJobDTO(
    val id: Long,
    val keyword: String,
    val status: String,
    val createdAt: java.time.ZonedDateTime,
    val totalLinks: Int?,
    val totalAds: Int?
) {
    // Companion object/factory method to convert Entity to DTO
    companion object {
        fun fromEntity(entity: KeywordSearch): KeywordJobDTO {
            return KeywordJobDTO(
                id = entity.id,
                keyword = entity.keyword,
                status = entity.status.name,
                createdAt = entity.createdAt,
                totalLinks = entity.totalLinks,
                totalAds = entity.totalAds
            )
        }
    }
}
