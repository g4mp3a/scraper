package com.scraper.worker.integration

import com.scraper.worker.service.BingScraper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.LoggerFactory

@EnabledIfSystemProperty(named = "runLiveScraperTest", matches = "true")
class BingScraperLiveIT {

    private val logger = LoggerFactory.getLogger(javaClass)

    // We use the real Ktor client and the real Scraper here
    private val client = HttpClient(CIO)
    private val scraper = BingScraper(client)

//    @Test
    fun `should extract links from actual Bing website`() {
        val keyword = "kotlin coroutines"

        logger.info("Performing live scrape for: $keyword")
        val result = scraper.scrape(keyword)

        logger.info("Scrape result: links=${result.linkCount}, ads=${result.adCount}")

        // Assertions: Bing almost always returns at least 5-10 organic links
        assertTrue(result.linkCount > 0, "Should have found at least one organic link")
        assertTrue(result.fullHtml.contains("<html>", ignoreCase = true), "Should have captured HTML")
    }
}
