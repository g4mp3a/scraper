package com.scraper.worker.service.exception

// Dedicated exception for failures that persist after all in-app retries
class PermanentScrapingFailureException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause)

// Custom exception to signal transient errors that should trigger a retry
class BingTransientException(message: String) : RuntimeException(message)

sealed class ScraperException(message: String) : RuntimeException(message)

class ScraperTimeoutException(message: String) : ScraperException(message)
class ScraperBlockedException(message: String) : ScraperException(message)
class ScraperLayoutException(message: String) : ScraperException(message)