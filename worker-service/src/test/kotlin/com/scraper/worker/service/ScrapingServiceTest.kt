package com.scraper.worker.service

import com.scraper.worker.domain.search.*
import com.scraper.worker.dto.KeywordJobPayload
import com.scraper.worker.dto.ScrapeResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import java.time.ZonedDateTime

@ExtendWith(MockitoExtension::class)
class ScrapingServiceTest {

    @Mock
    private lateinit var jobPersistenceService: JobPersistenceService

    @Mock
    private lateinit var bingScraper: BingScraper

    @InjectMocks
    private lateinit var scrapingService: ScrapingService

    private val testJobId = 101L
    private val testKeyword = "kotlin tutorial"
    private val mockPayload = KeywordJobPayload(testJobId, "user-1", testKeyword)

    private fun createMockJob(status: SearchStatus) = KeywordSearch(
        id = testJobId,
        keyword = testKeyword,
        status = status,
        userId = "user-1",
        createdAt = ZonedDateTime.now()
    )

    @Test
    fun `processJob should update job status to COMPLETED on success`() {
        // Arrange
        val processingJob = createMockJob(SearchStatus.PROCESSING)
        val scrapeResult = ScrapeResult(5, 1, "<html>...</html>")

        `when`(jobPersistenceService.updateStatus(testJobId, SearchStatus.PROCESSING))
            .thenReturn(processingJob)
        `when`(bingScraper.scrape(testKeyword)).thenReturn(scrapeResult)

        // Act
        scrapingService.processJob(mockPayload)

        // Assert
        verify(jobPersistenceService).updateStatus(testJobId, SearchStatus.PROCESSING)
        verify(jobPersistenceService).finalizeJob(testJobId, scrapeResult)
    }

    @Test
    fun `processJob should update job status to FAILED on scraper failure`() {
        // Arrange
        val processingJob = createMockJob(SearchStatus.PROCESSING)
        `when`(jobPersistenceService.updateStatus(testJobId, SearchStatus.PROCESSING))
            .thenReturn(processingJob)
        `when`(bingScraper.scrape(testKeyword)).thenThrow(RuntimeException("Scraper crashed"))

        // Act
        scrapingService.processJob(mockPayload)

        // Assert
        verify(jobPersistenceService).updateStatus(testJobId, SearchStatus.PROCESSING)
        verify(jobPersistenceService).updateStatus(testJobId, SearchStatus.FAILED)
    }

    @Test
    fun `processJob should not attempt scraping if job is not found`() {
        // Arrange
        `when`(jobPersistenceService.updateStatus(testJobId, SearchStatus.PROCESSING))
            .thenReturn(null)

        // Act
        scrapingService.processJob(mockPayload)

        // Assert
        verify(bingScraper, never()).scrape(anyString())
        verify(jobPersistenceService, never()).finalizeJob(eq(testJobId), anyKotlin(ScrapeResult::class.java))
    }
}


private fun <T> anyKotlin(cls: Class<T>): T {
    any<T>(cls)
    return null as T
}