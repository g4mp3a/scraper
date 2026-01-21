package com.scraper.worker.service

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SimpleKtorJsoupScraperTest {
    @Test
    fun `scrape should return empty results instead of crashing when HTML is unexpected`() = runBlocking {
        // Arrange: Mock a successful 200 OK but with "Garbage" HTML
        val mockEngine = MockEngine { request: HttpRequestData ->
            respond(
                content = "<html><body><h1>Access Denied or Weird Layout</h1></body></html>",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val client = HttpClient(mockEngine)
        val scraper = SimpleKtorJsoupScraper(client)

        // Act
        val result = scraper.scrape("test query")

        // Assert: It should handle the lack of tags gracefully
        assertEquals(0, result.linkCount)
        assertEquals(0, result.adCount)
        assert(result.fullHtml.contains("Access Denied"))
    }

    @Test
    fun `scrape should throw exception on timeout for ScrapingService to handle`() = runBlocking {
        // Arrange: Mock an engine that throws a timeout
        val mockEngine = MockEngine { _ ->
            throw HttpRequestTimeoutException("Timeout", null)
        }
        val client = HttpClient(mockEngine)
        val scraper = SimpleKtorJsoupScraper(client)

        // Act & Assert
        assertThrows<HttpRequestTimeoutException> {
            scraper.scrape("test query")
        }
    }
}
