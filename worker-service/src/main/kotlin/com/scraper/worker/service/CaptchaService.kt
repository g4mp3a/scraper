package com.scraper.worker.service

import com.microsoft.playwright.Page
import org.springframework.stereotype.Service

interface CaptchaService {
    fun solve(page: Page): Boolean
}

/**
 * Default implementation for Beta.
 * Simply logs the requirement without spending money.
 */
@Service
class NullCaptchaService : CaptchaService {
    private val logger = org.slf4j.LoggerFactory.getLogger(NullCaptchaService::class.java)

    override fun solve(page: Page): Boolean {
        logger.warn("Captcha encountered. NullCaptchaService is active - no solution attempted.")
        return false
    }
}
