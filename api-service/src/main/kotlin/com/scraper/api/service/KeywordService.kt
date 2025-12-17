package com.scraper.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.scraper.api.domain.outbox.OutboxEvent
import com.scraper.api.domain.outbox.OutboxEventRepository
import com.scraper.api.domain.search.KeywordSearch
import com.scraper.api.domain.search.KeywordSearchRepository
import com.scraper.api.domain.user.AppUser
import org.springframework.data.domain.Page
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.BufferedReader
import java.io.InputStreamReader

@Service
class KeywordService(
    private val keywordSearchRepository: KeywordSearchRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper // Needed to create the event JSON payload
) {

    private val MAX_KEYWORDS = 100
    private val EVENT_TYPE = "KEYWORD_UPLOADED"
    private val AGGREGATE_TYPE = "KEYWORD_SEARCH"

    /**
     * Handles the file upload, parses keywords, saves to DB, and creates outbox events.
     * This entire method must run in a single database transaction.
     */
    @Transactional
    fun processKeywordFileUpload(file: MultipartFile, user: AppUser): List<Long> {
        val keywords = parseCsvFile(file)
        if (keywords.isEmpty()) {
            throw IllegalArgumentException("CSV file contains no valid keywords.")
        }

        // List to hold IDs of successfully created jobs
        val newJobIds = mutableListOf<Long>()

        // 1. Transactional Write: Save KeywordSearch and OutboxEvent atomically
        for (keyword in keywords) {
            // A. Create the Keyword Search Job (status=PENDING by default)
            val newJob = KeywordSearch(user = user, keyword = keyword)
            val savedJob = keywordSearchRepository.save(newJob)

            // B. Create the Outbox Event (linked to the job ID)
            val payloadNode = objectMapper.createObjectNode().apply {
                put("searchId", savedJob.id)
                put("userId", user.firebaseUid)
                put("keyword", keyword)
            }

            val outboxEvent = OutboxEvent(
                aggregateType = AGGREGATE_TYPE,
                aggregateId = savedJob.id.toString(),
                type = EVENT_TYPE,
                payload = payloadNode
            )
            outboxEventRepository.save(outboxEvent)

            newJobIds.add(savedJob.id)
        }

        // Transaction commits here, ensuring both keyword jobs and events are persisted together.
        return newJobIds
    }

    /**
     * Retrieves all keyword search jobs for the given user, ordered by creation time.
     * Used by the main results viewing screen and the "Refresh" button.
     */
    @Transactional(readOnly = true)
    fun getJobsByUserId(firebaseUid: String): List<KeywordSearch> {
        return keywordSearchRepository.findAllByUser_FirebaseUidOrderByCreatedAtDesc(firebaseUid)
    }

    /**
     * Retrieves a single, detailed search job result for the given ID and user.
     * Ensures the user can only view their own data (Authorization check).
     */
    @Transactional(readOnly = true)
    fun getJobDetails(jobId: Long, firebaseUid: String): KeywordSearch? {
        return keywordSearchRepository.findByIdAndUser_FirebaseUid(jobId, firebaseUid).orElse(null)
    }

    /**
     * Searches across all keyword reports for a given query, scoped to the user, with pagination.
     */
    @Transactional(readOnly = true)
    fun searchJobsByKeyword(firebaseUid: String, query: String, page: Int, size: Int): Page<KeywordSearch> {
        // Create a Pageable object for pagination
        val pageable: Pageable = PageRequest.of(page, size)

        return keywordSearchRepository.findAllByUser_FirebaseUidAndContainingKeywordIgnoreCase(
            firebaseUid,
            query,
            pageable
        )
    }

    // Simple parser for reading one keyword per line from a CSV file
    private fun parseCsvFile(file: MultipartFile): List<String> {
        val keywords = mutableListOf<String>()
        BufferedReader(InputStreamReader(file.inputStream)).use { reader ->
            reader.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct() // Ensure no duplicate keywords in the batch
                .limit(MAX_KEYWORDS.toLong()) // Enforce max limit of 100 keywords
                .forEach { keywords.add(it) }
        }
        return keywords
    }
}
