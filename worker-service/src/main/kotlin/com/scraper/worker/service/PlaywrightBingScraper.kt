package com.scraper.worker.service

import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.Page
import com.scraper.worker.dto.ScrapeResult
import com.scraper.worker.service.exception.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Recover
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import java.net.URLEncoder
import kotlin.random.Random

@Service
class PlaywrightBingScraper(
    private val browserProvider: PlaywrightBrowserProvider,
    private val deviceEmulator: DeviceEmulator
) {
    private val logger = LoggerFactory.getLogger(PlaywrightBingScraper::class.java)

    @Value("\${scraper.bing.handle-consent:false}")
    private val handleConsent: Boolean = false

    @Retryable(
        value = [ScraperTimeoutException::class, ScraperLayoutException::class],
        maxAttempts = 2,
        backoff = Backoff(delay = 3000) // 3s delay
    )
    fun scrape(keyword: String): ScrapeResult {
        val device = deviceEmulator.getRandomDevice()

        return try {
            browserProvider.executeInNewPage(device) { page ->
                // Browser stealth
                applyStealth(page)

                // Resource Optimization (Block Images/Media)
                page.route("**/*.{png,jpg,jpeg,svg,webp,gif}") { it.abort() }

                // Navigation
                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val url = "https://www.bing.com/search?q=$encodedKeyword"

                // Initial jitter
                Thread.sleep(Random.nextLong(200, 800))

                try {
                    page.navigate(url)
                } catch (e: Exception) {
                    logger.error("Navigation failed for $keyword: ${e.message}")
                    throw ScraperTimeoutException("Initial navigation timed out.")
                }

                // Captcha/Consent Banner
                if (handleConsent) {
                    clickConsentBanner(page)
                }

                // Human behaviour
                performHumanActions(page)

                // Detection and validation
                validatePageContent(page, keyword)

                // Extraction
                val counts = extractFunctionalLinks(page)

                ScrapeResult(
                    linkCount = counts["organic"] ?: 0,
                    adCount = counts["ads"] ?: 0,
                    fullHtml = page.content() // TODO post initial testing add .take(5000)
                )
            }
        } catch (e: Exception) {
            // Logging for local/beta monitoring phase
            // TODO Add prometheus support in a later phase
            logger.error("Scrape failure for [$keyword] | Type: ${e.javaClass.simpleName} | Reason: ${e.message}")
            throw e
        }
    }

    @Recover
    fun recoverScrape(e: Exception, keyword: String): ScrapeResult {
        logger.error("PERMANENT FAILURE: All retries failed for keyword: $keyword. Reason: ${e.message}")
        // Throw custom exception to signal the permanent failure status
        throw PermanentScrapingFailureException("Failed to scrape after 3 attempts: $keyword", e)
    }

    /**
     * Stealth Initialization
     * By default, headless browsers set navigator.webdriver to true.
     * Many bot-detection scripts check this first.
     * We set it to `undefined` to mimic a regular user.
     * Real Google Chrome adds a window.chrome object to the global scope.
     * Headless Chromium often lacks this or has a different structure.
     * Adding this "dummy" object satisfies simple consistency checks.
     * A perfectly empty plugin list is a common fingerprint for a "fresh" automated instance.
     * We provide a fake non-empty array to look more "human."
     */
    private fun applyStealth(page: Page) {
        page.addInitScript("""
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            window.chrome = { runtime: {} };
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
        """)
    }

    /**
     * In many regions (especially if Cloud Run instance is in Europe/Asia),
     * Bing will throw a massive modal blocking the results until you "Accept All."
     * If that banner is up, page.evaluate might still work, but NETWORKIDLE will take forever,
     * and some results might be hidden.
     */
    private fun clickConsentBanner(page: Page) {
        // Instant "Nuclear" Check - No waiting/blocking
        page.evaluate("""() => {
            const selectors = ['button#bnp_btn_accept', 'button[id*="accept"]', '.bnp_btn_accept'];
            for (const selector of selectors) {
                const btn = document.querySelector(selector);
                if (btn && typeof btn.click === 'function') {
                    btn.click();
                    return true;
                }
            }
            return false;
        }""")
    }

    /**
     * Wait for the initial body to exist so we aren't "scrolling" on a blank white screen
     * Perform 1-3 "Human Actions"
     */
    private fun performHumanActions(page: Page) {
        page.waitForSelector("body")
        repeat(Random.nextInt(1, 3)) {
            // Random Smooth Scroll
            // Using mouse.wheel is fine, but doing it in small increments mimics a finger/wheel better
            val scrollAmount = Random.nextDouble(200.0, 500.0)
            val steps = Random.nextInt(2, 6)
            for (i in 1..steps) {
                page.mouse().wheel(0.0, scrollAmount / steps)
                Thread.sleep(Random.nextLong(50, 150))
            }

            // 3. Random Mouse Move with "Jiggle"
            // Instead of one jump, we move to a target via an intermediate point
            val targetX = Random.nextDouble(100.0, 800.0)
            val targetY = Random.nextDouble(100.0, 600.0)
            page.mouse().move(targetX / 2, targetY / 2) // Intermediate
            Thread.sleep(Random.nextLong(100, 300))
            page.mouse().move(targetX, targetY)         // Final

            // 4. Random Pause (Thinking time)
            Thread.sleep(Random.nextLong(500, 1200))
        }
    }

    /**
     * Wait for ACF/Magazine hydration
     * Handle common "Challenge/Bot" selectors when looking for results
     */
    private fun validatePageContent(page: Page, keyword: String) {
        try {
            // Fallback: If network never goes idle, wait for the results container
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(2000.0))
            } catch (e: Exception) {
                logger.debug("Network didn't go idle for $keyword, checking selectors...")
            }

            // We wait for EITHER the results OR a sign that we are blocked
            page.waitForSelector("#b_results, #challenge-form, .hCaptcha, #b_notificationContainer_lbl",
                Page.WaitForSelectorOptions().setTimeout(2000.0))

            if (page.locator("#challenge-form, .hCaptcha").isVisible) {
                throw ScraperBlockedException("CAPTCHA/Challenge detected.")
            }

            if (!page.locator("#b_results").isVisible) {
                throw ScraperLayoutException("Results container (#b_results) missing.")
            }
        } catch (e: com.microsoft.playwright.TimeoutError) {
            throw ScraperTimeoutException("Timed out waiting for results layout.")
        }
    }

    /**
     * Extract data about ads and organic search results from the page.
     */
    private fun extractFunctionalLinks(page: Page): Map<String, Int> {
        return page.evaluate("""
            () => {
                const links = Array.from(document.querySelectorAll('a[href*="/ck/a?!"], a[href*="/aclk?"]'));
                let organic = 0;
                let ads = 0;
            
                links.forEach(link => {
                    try {
                        const urlObj = new URL(link.href, window.location.origin);
                        
                        // Ad Pattern
                        if (urlObj.pathname.includes('/aclk')) {
                            ads++;
                            return;
                        }
            
                        // Organic Pattern
                        if (urlObj.pathname.includes('/ck/a')) {
                            const uParam = urlObj.searchParams.get('u');
                            if (!uParam) return;
            
                            // Internal Bing links in the 'u' param almost always start with 'a1L' because they are relative paths.
                            // External links are always either 'HR0cHM' (https) or 'HR0cDov' (http)
                            // Therefore check if it starts with the Version (a1) + Type (a) + http/https
                            const isExternalHttps = uParam.startsWith('a1aHR0cHM');
                            const isExternalHttp = uParam.startsWith('a1aHR0cDov');
                            
                            const isInternal = !(isExternalHttps || isExternalHttp);   

                            if (!isInternal) {
                                organic++;
                            }
                        }
                    } catch (e) { /* ignore malformed urls */ }
                });
            
                return { organic, ads };
            }
        """) as Map<String, Int>
    }
}
