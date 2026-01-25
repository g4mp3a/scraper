package com.scraper.worker.service

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.Path

@Service
class PlaywrightBrowserProvider {

    private val logger = LoggerFactory.getLogger(PlaywrightBrowserProvider::class.java)

    private val RECYCLE_THRESHOLD = 500
    private val TTL_MILLIS = 3600_000L // 1 Hour TTL for the browser process

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var context: BrowserContext? = null


    private val scrapeCount = AtomicInteger(0)
    private val birthTime = AtomicLong(0L)
    private val lock = ReentrantLock()

    private val proxyProvider: ProxyProvider = DirectConnectionProxyProvider()

    fun getBrowser(): Browser {
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
        return browser!!
    }

    /**
     * Managed execution scope for a single scrape.
     */
    fun <T> executeInNewPage(device: DeviceProfile, block: (Page) -> T): T {
        // Create context with essential production flags
//        val context = getBrowser()
//            .newContext(
//            Browser.NewContextOptions()
//                .setJavaScriptEnabled(true)
//                // Ignore SSL cert issues coz a job should never fail just because a tracking pixel has an expired SSL cert
//                .setIgnoreHTTPSErrors(true)
//                // While false is the default, being explicit is safer for a production worker
//                // to ensure we never accidentally inherit an offline state from a previous crash
//                .setOffline(false)
//                .setProxy(proxyProvider.getNextProxy())
//                .setUserAgent(device.userAgent)
//                .setViewportSize(device.width, device.height)
//                .setLocale("en-US")
//                .setTimezoneId("America/Los_Angeles")
//                .setAcceptDownloads(true)
//                .setViewportSize(1920, 1080)
//                .setScreenSize(1920, 1080)
//        )

        getBrowser()

        val contxt = this.context ?: throw IllegalStateException("Context not initialized")
        // This ensures that the context and page are always closed properly (use block), preventing memory leaks if a scrape fails midway
//        return contxt.use { ctx ->
        return  contxt.newPage().use { page ->
                // Listener to pipe browser console messages to our app logs/terminal
                page.onConsoleMessage { println("BROWSER: ${it.text()}") }
                Thread.sleep(5000)
                block(page)

            }
    }

    private fun recycleBrowser(reason: String) {
        logger.info("Recycling Chromium. Reason: $reason")
        try {
            browser?.close()
            playwright?.close()
        } catch (e: Exception) {
            logger.warn("Error during browser teardown: ${e.message}")
        }

        val pw = Playwright.create()
        val launchOptions = BrowserType.LaunchPersistentContextOptions() // BrowserType.LaunchOptions()
            .setHeadless(false)
            .setArgs(listOf(
                // Required for Cloud Run
                "--no-sandbox",
                // In Cloud Run, there is no GPU. Disabling GPU support saves 80-120MB of RAM per browser instance
                //"--disable-gpu",
                // In Docker/Cloud Run, shared memory (shm) is tiny, typically 64MB. Bing will crash when loading and rendering
                // heavy pages with lots of images and complex JS. Chromium will crash. Use disk based /tmp instead.
                // Cloud Run provides a much larger disk-based temp space.
                //"--disable-dev-shm-usage",
                // No plugins or extensions
                //"--disable-extensions",
                // No background updates
                //"--disable-component-update",
                // No internal telemetry
                //"--disable-background-networking",
                // No Google account sync
                //"--disable-sync",
                // Dont even init audio drivers
                //"--mute-audio",
                // Skip welcome splash
                //"--no-first-run",
                // In a headless environment, all windows are technically "occluded" (not visible)
                // This flag ensures the browser treats the scrape task as a high-priority foreground task
                //"--disable-backgrounding-occluded-windows",
                // Tells the rendering engine not to "sleep" or throttle the CPU for the tab
                //"--disable-renderer-backgrounding",
                // For local testing using the system's actual GL drivers
                "--use-gl=desktop",
                "--ignore-certificate-errors",
                "--disable-blink-features=AutomationControlled"
            ))

        this.playwright = pw
        //this.browser = pw.chromium().launch(launchOptions)
        this.context = pw.chromium().launchPersistentContext(Path("/Users/gp/playwright-chromium"), launchOptions)
        if (this.context == null) throw IllegalStateException("Context not initialized")
        // Add this to prevent the "Chrome is being controlled by automated software" bar
        this.context?.addInitScript("Object.defineProperty(navigator, 'languages', {get: () => ['en-US', 'en']});")
//        this.browser = this.context?.pages()?.firstOrNull()?.browser() ?: this.context?.browser()
        this.browser = this.context?.browser()
        this.scrapeCount.set(0)
        this.birthTime.set(System.currentTimeMillis())
        logger.info("Chromium instance warm: ${browser?.version()}")
    }

    @PreDestroy
    fun shutdown() {
        lock.withLock {
            browser?.close()
            playwright?.close()
        }
    }
}
