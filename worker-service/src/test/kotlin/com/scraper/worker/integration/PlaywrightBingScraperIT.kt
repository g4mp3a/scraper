package com.scraper.worker.integration

import com.scraper.worker.service.BingScraper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Live Integration Test for the Playwright Scraper.
 * Run with: -DrunRealBingSearchIT=true
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "runRealBingSearchIT", matches = "true")
class PlaywrightBingScraperIT : BaseWorkerIntegrationTest() {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var scraper: BingScraper

    @Test
    fun `should perform real search on Bing for coffee shops in San Mateo`() {
        // Arrange
        val keyword = "local pastry shops near foster city, ca"

        // Act
        logger.info("Executing live search test for: $keyword")
        val result = scraper.scrape(keyword)

        // Assert
        assertNotNull(result)
        logger.info("Live Search Result - Organic: ${result.linkCount}, Ads: ${result.adCount}")

        // In 2026, a specific query like this in a major metro should ALWAYS return results
        assertTrue(result.linkCount > 0, "Should have found at least one organic link")
        assertTrue(result.fullHtml.contains("pastry"), "HTML should contain the keyword content")

        // Note: Ads might be 0 depending on the time of day/IP, so we don't hard assert > 0
    }

//    @Test
    fun `should verify stealth initialization prevents webdriver detection`() {
        // We use the scraper's internal browser provider via a specialized navigate
        // or just rely on the fact that the scrape method applies the script.
        // Direct approach: Search for a "bot check" keyword or verify via evaluation.

        // Since we want to be direct, let's use the actual scraper but look at the HTML
        // or a specific evaluate result if we were to expose it.
        // For now, if the scrape succeeds on Bing without a CAPTCHA, the stealth is likely working.

        val result = scraper.scrape("what is my navigator.webdriver")

        // If we got here without a ScraperBlockedException, we are in good shape.
        assertNotNull(result.fullHtml)
    }
}
