package com.scraper.worker.service

import com.scraper.worker.domain.search.KeywordSearchRepository
import com.scraper.worker.domain.search.SearchStatus
import com.scraper.worker.dto.KeywordJobPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime
import java.time.ZoneOffset
import org.springframework.scheduling.annotation.Async // CRITICAL

@Service
class ScrapingService(
    private val keywordSearchRepository: KeywordSearchRepository,
    private val bingScraper: BingScraper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The job processing runs ASYNCHRONOUSLY.
     * The final outcome is ONLY recorded in the database.
     */
    @Async
    @Transactional
    fun processJob(payload: KeywordJobPayload) {
        val jobId = payload.searchId
        val keyword = payload.keyword

        if (keyword.isBlank()) {
            logger.warn("Job ID $jobId has empty keyword. Skipping.")
            return
        }

        val job = keywordSearchRepository.findById(jobId).orElse(null)
        if (job == null) {
            logger.warn("Job ID $jobId not found. Skipping.")
            return
        }

        // 1. Mark job as PROCESSING (Synchronous write before scraping)
        val jobToUpdate = job.copy(status = SearchStatus.PROCESSING)
        try {
            keywordSearchRepository.save(jobToUpdate)
        } catch (e: Exception) {
            logger.error("Failed to set initial PROCESSING status for job $jobId", e)
            throw e // Re-throw to ensure the @Async/Transactional handles it
        }

        try {
            logger.info("Starting scrape for Job ID: $jobId, Keyword: ${payload.keyword}")

            // 2. Perform the Scraping (internally it retries via @Retryable)
            val result = bingScraper.scrape(payload.keyword)

            // 3. Mark job as COMPLETED and save results
            val completedJob = jobToUpdate.copy(
                status = SearchStatus.COMPLETED,
                totalLinks = result.linkCount,
                totalAds = result.adCount,
                fullHtml = result.fullHtml,
                completedAt = ZonedDateTime.now(ZoneOffset.UTC)
            )
            keywordSearchRepository.save(completedJob)
            logger.info("Job ID $jobId completed.")

        } catch (e: Exception) {
            // 4. Handle final failure after all in-app retries are exhausted.
            logger.error("Permanent scraping failure for Job ID $jobId: ${e.message}")

            val failedJob = jobToUpdate.copy(
                status = SearchStatus.FAILED,
                completedAt = ZonedDateTime.now(ZoneOffset.UTC)
            )
            keywordSearchRepository.save(failedJob)

            // NOTE: The exception is logged by the AsyncUncaughtExceptionHandler,
            // but it CANNOT be propagated back to the controller or Pub/Sub.
        }
    }
}
