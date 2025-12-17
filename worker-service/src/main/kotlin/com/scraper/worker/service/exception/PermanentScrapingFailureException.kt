package com.scraper.worker.service.exception

// Dedicated exception for failures that persist after all in-app retries
class PermanentScrapingFailureException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause)
