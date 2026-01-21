package com.scraper.worker.config

import com.scraper.worker.service.PlaywrightBrowserProvider
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration

/**
 * Configuration class to manage the lifecycle of the Playwright infrastructure.
 * The actual browser logic is delegated to the PlaywrightBrowserProvider service.
 */
@Configuration
class PlaywrightConfig(
    private val browserProvider: PlaywrightBrowserProvider
) {
    private val logger = LoggerFactory.getLogger(PlaywrightConfig::class.java)

    /**
     * Ensures that when the Spring ApplicationContext shuts down,
     * the underlying Playwright processes are terminated.
     */
    @PreDestroy
    fun onShutdown() {
        logger.info("Spring context is closing. Initiating Playwright cleanup via Provider...")
        browserProvider.shutdown()
    }
}
