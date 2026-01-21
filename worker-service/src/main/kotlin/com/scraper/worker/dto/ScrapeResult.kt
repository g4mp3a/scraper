package com.scraper.worker.dto

data class ScrapeResult(
    val linkCount: Int,
    val adCount: Int,
    val fullHtml: String
)
