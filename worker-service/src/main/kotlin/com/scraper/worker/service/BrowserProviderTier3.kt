package com.scraper.worker.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.Path

@Service
class BrowserProviderTier3(
    @Value("\${scraper.patchright.path}") private val patchrightPath: String,
    @Value("\${scraper.headless}") private val isHeadless: Boolean,
    @Value("\${scraper.gpu-mode}") private val gpuMode: String,
    @Value("\${scraper.profile-base-path}") private val profilePathString: String,
    private val deviceEmulator: DeviceEmulator,
    private val proxyProvider: ProxyProvider,
) {
    private val logger = LoggerFactory.getLogger(BrowserProviderTier3::class.java)

    private val RECYCLE_THRESHOLD = 100
    private val TTL_MILLIS = 3600_000L // 1 Hour TTL for the browser process

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var context: BrowserContext? = null


    private val scrapeCount = AtomicInteger(0)
    private val birthTime = AtomicLong(0L)
    private val lock = ReentrantLock()

    fun initBrowser() {
        val currentCount = scrapeCount.incrementAndGet()
        val age = System.currentTimeMillis() - birthTime.get()

        // Recycle if:
        // 1. Browser isn't init yet
        // 2. We hit the request limit
        // 3. The process is "too old" (TTL)
        if (browser == null || currentCount >= RECYCLE_THRESHOLD || age >= TTL_MILLIS) {
            lock.withLock {
                val ageInLock = System.currentTimeMillis() - birthTime.get()
                if (browser == null || scrapeCount.get() >= RECYCLE_THRESHOLD || ageInLock >= TTL_MILLIS) {
                    val reason = when {
                        browser == null -> "Initial Launch"
                        scrapeCount.get() >= RECYCLE_THRESHOLD -> "Scrape Limit exceeded, count: (${scrapeCount.get()})"
                        else -> "TTL Expired, age: (${ageInLock / 1000}s)"
                    }
                    recycleBrowser(reason)
                }
            }
        }
    }

    /**
     * Managed execution scope for a single scrape.
     */
    fun <T> executeInNewPage(block: (Page) -> T): T {
        initBrowser()

        // This ensures that the context and page are always closed properly (use block), preventing memory leaks if a scrape fails midway
        return  this.context?.newPage().use { page ->
                page ?: throw IllegalStateException("PlaywrightNPE: Playwright page is null!!")
                // Listener to pipe browser console messages to our app logs/terminal
                page.onConsoleMessage { logger.debug("BROWSER: ${it.text()}") }
                page.waitForLoadState(LoadState.DOMCONTENTLOADED)
                block(page)
            }
    }

    private fun recycleBrowser(reason: String) {
        logger.info("Recycling Chromium. Reason: $reason")
        try {
            browser?.close()
            context?.close()
            playwright?.close()
        } catch (e: Exception) {
            logger.warn("Error during browser teardown: ${e.message}")
        }

        val device = deviceEmulator.getRandomDevice()
        val pw = Playwright.create()

        val args = mutableListOf(
            // Required for Cloud Run
            "--no-sandbox",
            // In Docker/Cloud Run, shared memory (shm) is tiny, typically 64MB. Bing will crash when loading and rendering
            // heavy pages with lots of images and complex JS. Chromium will crash. Use disk based /tmp instead.
            // Cloud Run provides a much larger disk-based temp space.
            "--disable-dev-shm-usage",
            // No Google account sync
            "--disable-sync",
            // Skip welcome splash
            "--no-first-run",
            // In a headless environment, all windows are technically "occluded" (not visible)
            // This flag ensures the browser treats the scrape task as a high-priority foreground task
            "--disable-backgrounding-occluded-windows",
            // Tells the rendering engine not to "sleep" or throttle the CPU for the tab
            "--disable-renderer-backgrounding",
            // Injected from profile. --disable-gpu in prod, --use-gl=desktop locally
            gpuMode,
        )

        val launchOptions = BrowserType.LaunchPersistentContextOptions()
            .setExecutablePath(Path(patchrightPath))
            .setHeadless(isHeadless)
            .setJavaScriptEnabled(true)
            // Ignore SSL cert issues coz a job should never fail just because a tracking pixel has an expired SSL cert
            .setIgnoreHTTPSErrors(true)
            // While false is the default, being explicit is safer for a production worker
            // to ensure we never accidentally inherit an offline state from a previous crash
            .setOffline(false)
            .setAcceptDownloads(true)
            .setLocale("en-US")
            .setProxy(proxyProvider.getNextProxy())
            .setUserAgent(device.userAgent)
            .setViewportSize(device.width, device.height)
            .setArgs(args)

        // TODO: Change the way bing id profilePath is obtained after implementing Gardener.
        val profilePath = Path(profilePathString)

        this.playwright = pw
        this.context = pw.chromium().launchPersistentContext(profilePath, launchOptions)

        this.browser = if (this.context == null) throw NullPointerException("Context not initialized") else this.context!!.browser()
        this.scrapeCount.set(0)
        this.birthTime.set(System.currentTimeMillis())
        logger.info("Chromium instance warm: ${browser?.version()}")
    }

    @PreDestroy
    fun shutdown() {
        lock.withLock {
            browser?.close()
            context?.close()
            playwright?.close()
        }
    }
}
