package com.scraper.api.web.dto

data class PageResponseDTO<T>(
    val content: List<T>,
    val totalElements: Long,
    val totalPages: Int,
    val currentPage: Int,
    val pageSize: Int,
    val isLast: Boolean
)
