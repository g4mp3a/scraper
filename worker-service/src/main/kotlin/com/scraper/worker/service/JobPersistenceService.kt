package com.scraper.worker.service

import com.scraper.worker.domain.search.KeywordSearch
import com.scraper.worker.domain.search.KeywordSearchRepository
import com.scraper.worker.domain.search.SearchStatus
import com.scraper.worker.dto.ScrapeResult
import org.slf4j.LoggerFactory
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Service
class JobPersistenceService(private val repository: KeywordSearchRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Updates only the status of a job.
     */
    @Transactional
    fun updateStatus(jobId: Long, status: SearchStatus): KeywordSearch? {
        val job = repository.findById(jobId).orElse(null)
        if (job == null) {
            logger.warn("Job ID $jobId not found in database.")
            return null
        }
        val updatedJob = job.copy(status = status)
        return repository.save(updatedJob)
    }

    /**
     * Finalizes the job with results.
     * Retries up to 3 times on DB blips to protect the expensive scrape work.
     */
    @Transactional
    @Retryable(
        value = [org.springframework.dao.DataAccessException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 1000, multiplier = 2.0)
    )
    fun finalizeJob(jobId: Long, result: ScrapeResult) {
        repository.findById(jobId).ifPresent {
            val completedJob = it.copy(
                status = SearchStatus.COMPLETED,
                totalLinks = result.linkCount,
                totalAds = result.adCount,
                fullHtml = result.fullHtml,
                completedAt = ZonedDateTime.now(ZoneOffset.UTC)
            )
            repository.save(completedJob)
            logger.info("Job ID $jobId completed successfully.")
        }
    }
}
