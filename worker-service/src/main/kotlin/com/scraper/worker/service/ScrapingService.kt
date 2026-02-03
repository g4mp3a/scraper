package com.scraper.worker.service

import com.scraper.worker.domain.search.SearchStatus
import com.scraper.worker.dto.KeywordJobPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Async // CRITICAL

@Service
class ScrapingService(
    private val jobPersistenceService: JobPersistenceService,
    private val bingScraper: BingScraper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The job processing runs ASYNCHRONOUSLY.
     * The final outcome is ONLY recorded in the database.
     */
    @Async
    fun processJob(payload: KeywordJobPayload) {

        val jobId = payload.searchId
        val keyword = payload.keyword

        if (keyword.isBlank()) {
            logger.warn("Job ID $jobId has empty keyword. Skipping.")
            return
        }

        // 1. Mark job as PROCESSING (DB Txn 1) and verify existence
        val job = jobPersistenceService.updateStatus(jobId, SearchStatus.PROCESSING)
        if (job == null) {
            logger.warn("Job ID $jobId not found. Skipping.")
            return
        }

        try {
            logger.info("Starting scrape for Job ID: $jobId, Keyword: ${payload.keyword}")

            // 2. Perform the scraping (Long-running I/O, NO Transaction here)
            val result = bingScraper.scrape(keyword)

            // 3. Record outcome, mark job as COMPLETED (DB Txn 2)
            // Nested try-catch to differentiate DB save errors from Scraper errors
            try {
                jobPersistenceService.finalizeJob(jobId, result)
            } catch (dbEx: Exception) {
                logger.error("SCRAPE SUCCESS BUT SAVE FAILED for Job ID $jobId: ${dbEx.message}")
                throw dbEx // Bubble up to mark as FAILED
            }

        } catch (e: Exception) {
            // 4. Handle final failure after all in-app retries are exhausted.
            logger.error("Permanent failure for Job ID $jobId: ${e.message}")
            try {
                jobPersistenceService.updateStatus(jobId, SearchStatus.FAILED)
            } catch (dbEx: Exception) {
                logger.error("CRITICAL: Could not set FAILED status for $jobId", dbEx)
            }

            // NOTE: The exception is logged by the AsyncUncaughtExceptionHandler,
            // but it CANNOT be propagated back to the controller or Pub/Sub.
        }
    }
}
