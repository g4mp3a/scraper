package com.scraper.worker.service

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.springframework.retry.annotation.Retryable
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Recover
import org.springframework.web.client.ResourceAccessException
import com.scraper.worker.service.exception.PermanentScrapingFailureException
import com.scraper.worker.dto.ScrapeResult
import com.scraper.worker.service.exception.BingTransientException

@Component
class SimpleKtorJsoupScraper(private val httpClient: HttpClient) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.1 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
    )

    @Retryable(
        value = [ResourceAccessException::class, BingTransientException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 2000, multiplier = 2.0) // 2s, 4s delay
    )
    fun scrape(keyword: String): ScrapeResult = runBlocking {
        // Anti-Rate-Limit: Randomized Delay
        delay(Random.nextLong(1000, 3000))

        val encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())
        val searchUrl = "https://www.bing.com/search?q=$encodedKeyword"

        try {
            val response: HttpResponse = httpClient.get(searchUrl) {
                // Anti-Rate-Limit: Random User-Agent
                header(HttpHeaders.UserAgent, USER_AGENTS.random())
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }

            if (response.status == HttpStatusCode.ServiceUnavailable || response.status == HttpStatusCode.TooManyRequests) {
                logger.warn("Transient Bing error for '$keyword'. Attempting retry.")
                // Throw transient exception to engage @Retryable
                throw BingTransientException("Transient error: ${response.status.value}")
            }

            if (response.status != HttpStatusCode.OK) {
                // Throw fatal exception (not retried by @Retryable)
                throw Exception("Fatal HTTP status error: ${response.status}")
            }

            val htmlContent = response.bodyAsText()
            return@runBlocking parseHtml(htmlContent)

        } catch (e: Exception) {
            // Rethrow all exceptions for Spring Retry or the caller to handle
            throw e
        }
    }

    @Recover
    fun recoverScrape(e: Exception, keyword: String): ScrapeResult {
        logger.error("PERMANENT FAILURE: All retries failed for keyword: $keyword. Reason: ${e.message}")
        // Throw custom exception to signal the permanent, logged failure status
        throw PermanentScrapingFailureException("Failed to scrape after 3 attempts: $keyword", e)
    }

    private fun parseHtml(html: String): ScrapeResult {
        val document = Jsoup.parse(html)

        // Count Links: Simple count of all anchor tags
        val linkCount = document.select("a").size

        // Count Ads: Approximate selectors for Bing ads
        val adSelectors = listOf(".b_ad", ".b_adData", "li.b_ad")
        val adCount = adSelectors.sumOf { document.select(it).size }

        return ScrapeResult(
            linkCount = linkCount,
            adCount = adCount,
            fullHtml = html
        )
    }
}
