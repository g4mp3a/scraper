package com.scraper.worker.service

import com.scraper.worker.domain.search.*
import com.scraper.worker.dto.KeywordJobPayload
import com.scraper.worker.service.exception.PermanentScrapingFailureException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import java.time.ZonedDateTime
import java.util.*

@ExtendWith(MockitoExtension::class)
class ScrapingServiceTest {

    @Mock
    private lateinit var keywordSearchRepository: KeywordSearchRepository

    @Mock
    private lateinit var bingScraper: BingScraper

    @InjectMocks
    private lateinit var scrapingService: ScrapingService

    private val testJobId = 101L
    private val testKeyword = "kotlin tutorial"

    private val mockPayload = KeywordJobPayload(
        searchId = testJobId,
        userId = "user-1",
        keyword = testKeyword
    )

    private fun createMockJob(status: SearchStatus) = KeywordSearch(
        id = testJobId,
        keyword = testKeyword,
        status = status,
        userId = "user-1",
        createdAt = ZonedDateTime.now()
    )

    @Test
    fun `processJob should update job status to COMPLETED on success`() {
        val pendingJob = createMockJob(SearchStatus.PENDING)
        val scrapeResult = ScrapeResult(linkCount = 5, adCount = 1, fullHtml = "<html>...</html>")

        `when`(keywordSearchRepository.findById(testJobId)).thenReturn(Optional.of(pendingJob))
        `when`(bingScraper.scrape(testKeyword)).thenReturn(scrapeResult)

        scrapingService.processJob(mockPayload)

        val captor = ArgumentCaptor.forClass(KeywordSearch::class.java)
        // Verify specifically for KeywordSearch class to satisfy Kotlin types
        verify(keywordSearchRepository, times(2)).save(captor.capture())

        val capturedValues = captor.allValues
        assertEquals(SearchStatus.PROCESSING, capturedValues[0].status)
        assertEquals(SearchStatus.COMPLETED, capturedValues[1].status)
        assertEquals(5, capturedValues[1].totalLinks)
    }

    @Test
    fun `processJob should update job status to FAILED on permanent scraping failure`() {
        val processingJob = createMockJob(SearchStatus.PROCESSING)

        `when`(keywordSearchRepository.findById(testJobId)).thenReturn(Optional.of(processingJob))
        `when`(bingScraper.scrape(testKeyword)).thenThrow(
            PermanentScrapingFailureException("Banned permanently")
        )

        scrapingService.processJob(mockPayload)

        val captor = ArgumentCaptor.forClass(KeywordSearch::class.java)
        verify(keywordSearchRepository, times(2)).save(captor.capture())

        val capturedValues = captor.allValues
        assertEquals(SearchStatus.PROCESSING, capturedValues[0].status)
        assertEquals(SearchStatus.FAILED, capturedValues[1].status)
    }

    @Test
    fun `processJob should not attempt scraping if initial status update fails`() {
        // Arrange
        val pendingJob = createMockJob(SearchStatus.PENDING)
        `when`(keywordSearchRepository.findById(testJobId)).thenReturn(Optional.of(pendingJob))

        // Simulate DB failure on the first save
        // Using explicit class type in any() prevents IllegalStateException in Kotlin
        `when`(keywordSearchRepository.save(any(KeywordSearch::class.java)))
            .thenThrow(RuntimeException("DB Down"))

        // Act & Assert
        assertThrows<RuntimeException> {
            scrapingService.processJob(mockPayload)
        }

        // Verification happens AFTER the exception is caught and the state is clear
        verify(bingScraper, never()).scrape(anyString())
    }

    @Test
    fun `processJob should record FAILED status when scraper throws generic exception`() {
        val pendingJob = createMockJob(SearchStatus.PENDING)
        `when`(keywordSearchRepository.findById(testJobId)).thenReturn(Optional.of(pendingJob))
        `when`(bingScraper.scrape(anyString())).thenThrow(RuntimeException("Network error"))

        scrapingService.processJob(mockPayload)

        val captor = ArgumentCaptor.forClass(KeywordSearch::class.java)
        verify(keywordSearchRepository, times(2)).save(captor.capture())

        assertEquals(SearchStatus.FAILED, captor.allValues.last().status)
    }
}
