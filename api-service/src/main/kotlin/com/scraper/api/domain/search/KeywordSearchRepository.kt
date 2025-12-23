package com.scraper.api.domain.search

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import java.util.Optional

interface KeywordSearchRepository : JpaRepository<KeywordSearch, Long> {

    // Custom query to fetch all searches for a signed-in user, ordered by creation time
    fun findAllByUser_FirebaseUidOrderByCreatedAtDesc(firebaseUid: String): List<KeywordSearch>

    // Custom query to search keywords across all reports for the user
    /*
      Pageable will cause JPA to paginate results using a query similar to:
        SELECT *
        FROM keyword_search
        WHERE app_user_id = ? AND LOWER(keyword) LIKE LOWER(?)
        ORDER BY created_at DESC -- (If Pageable requests this sort)
        LIMIT ?
        OFFSET ?
     */
    fun findAllByUser_FirebaseUidAndKeywordContainingIgnoreCase(
        firebaseUid: String,
        keyword: String,
        pageable: Pageable
    ): Page<KeywordSearch>

    // Custom query to fetch a single search result by ID for the user
    fun findByIdAndUser_FirebaseUid(id: Long, firebaseUid: String): Optional<KeywordSearch>

    fun findByUser_FirebaseUid(firebaseUid: String): List<KeywordSearch>
}