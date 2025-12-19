package com.scraper.worker.domain.search

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import java.util.Optional

// The worker service does not need any custom methods.
interface KeywordSearchRepository : JpaRepository<KeywordSearch, Long>
