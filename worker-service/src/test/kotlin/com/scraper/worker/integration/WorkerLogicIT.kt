package com.scraper.worker.integration

import com.scraper.worker.dto.PubSubMessage
import com.scraper.worker.dto.ScrapeResult
import com.scraper.worker.service.PlaywrightBingScraper
import com.scraper.worker.service.exception.PermanentScrapingFailureException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.util.Base64

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkerLogicIT : BaseWorkerIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @MockBean
    private lateinit var bingScraper: PlaywrightBingScraper

    @Test
    fun `should process job from pending to completed via push endpoint`() {
        // Arrange
        val keyword = "pragmatic testing"
        val userId = "user-123"
        val jobId = setupJobInDb(userId, "test@example.com", keyword)

        `when`(bingScraper.scrape(anyString())).thenReturn(
            ScrapeResult(linkCount = 10, adCount = 2, fullHtml = "<html>test</html>")
        )

        // Act
        val response = sendPushRequest(jobId, userId, keyword)

        // Assert
        assertEquals(HttpStatus.OK, response.statusCode)
        awaitStatusUpdate(jobId, "COMPLETED")

        val finalLinkCount = jdbcTemplate.queryForObject(
            "SELECT total_links FROM keyword_search WHERE id = ?",
            Int::class.java,
            jobId
        )
        assertEquals(10, finalLinkCount)
    }

    @Test
    fun `should process to COMPLETED when bing returns zero results`() {
        // Arrange
        val keyword = "very obscure keyword"
        val userId = "user-zero"
        val jobId = setupJobInDb(userId, "zero@example.com", keyword)

        `when`(bingScraper.scrape(anyString())).thenReturn(
            ScrapeResult(linkCount = 0, adCount = 0, fullHtml = "<html>No results found</html>")
        )

        // Act
        sendPushRequest(jobId, userId, keyword)

        // Assert
        awaitStatusUpdate(jobId, "COMPLETED")
        val results = jdbcTemplate.queryForMap("SELECT total_links, total_ads FROM keyword_search WHERE id = ?", jobId)
        assertEquals(0, results["total_links"])
        assertEquals(0, results["total_ads"])
    }

    @Test
    fun `should transition to FAILED when scraper exhausts all retries`() {
        // Arrange
        val keyword = "blocked keyword"
        val userId = "user-retry-exhausted"
        val jobId = setupJobInDb(userId, "retry@example.com", keyword)

        // Mocking the specific exception thrown by your @Recover block
        `when`(bingScraper.scrape(anyString())).thenThrow(
            PermanentScrapingFailureException("Failed to scrape after 3 attempts: $keyword", null)
        )

        // Act
        sendPushRequest(jobId, userId, keyword)

        // Assert
        awaitStatusUpdate(jobId, "FAILED")
    }

    @Test
    fun `should transition to FAILED when an unexpected RuntimeException occurs`() {
        // Arrange
        val keyword = "crash keyword"
        val userId = "user-crash"
        val jobId = setupJobInDb(userId, "crash@example.com", keyword)

        `when`(bingScraper.scrape(anyString())).thenThrow(
            RuntimeException("Unexpected browser crash")
        )

        // Act
        sendPushRequest(jobId, userId, keyword)

        // Assert
        awaitStatusUpdate(jobId, "FAILED")
    }

    // --- Helpers ---

    private fun setupJobInDb(userId: String, email: String, keyword: String): Long {
        // Ensure user exists (ignore if already there from a previous test to be safe)
        jdbcTemplate.update(
            "INSERT INTO app_user (firebase_uid, email) VALUES (?, ?) ON CONFLICT DO NOTHING",
            userId, email
        )

        jdbcTemplate.update(
            "INSERT INTO keyword_search (app_user_id, keyword, status, created_at) VALUES (?, ?, ?::search_status, NOW())",
            userId, keyword, "PENDING"
        )

        val jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM keyword_search WHERE keyword = ? AND app_user_id = ? ORDER BY created_at DESC LIMIT 1",
                Long::class.java,
                keyword,
                userId
            )!!
        return jobId
    }

    private fun sendPushRequest(jobId: Long, userId: String, keyword: String): org.springframework.http.ResponseEntity<String> {
        val innerPayload = """{"searchId": $jobId, "userId": "$userId", "keyword": "$keyword"}"""
        val encodedData = Base64.getEncoder().encodeToString(innerPayload.toByteArray())

        val pushRequest = PubSubMessage(
            message = PubSubMessage.Message(data = encodedData, messageId = "msg-${System.currentTimeMillis()}")
        )

        return restTemplate.postForEntity("/pubsub/push", pushRequest, String::class.java)
    }
}
