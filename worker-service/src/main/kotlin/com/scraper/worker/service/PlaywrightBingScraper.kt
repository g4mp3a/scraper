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
        maxAttempts = 1,
        backoff = Backoff(delay = 3000) // 3s delay
    )
    fun scrape(keyword: String): ScrapeResult {
        val device = deviceEmulator.getRandomDevice()

        return try {
            browserProvider.executeInNewPage(device) { page ->
                // Browser stealth -- TODO: Commented out for local testing
                // applyStealth(page)

                // Resource Optimization (Block Images/Media) -- TODO commented out for local testing
//                page.route("**/*.{png,jpg,jpeg,svg,webp,gif}") { it.abort() }

                // Navigation
                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val url = "https://www.bing.com/search?q=$encodedKeyword"

                // Initial jitter
                Thread.sleep(Random.nextLong(2000, 8000))

                try {
                    // Warm the session
                    page.navigate("https://www.bing.com", Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED))
                    page.bringToFront()
                    Thread.sleep(Random.nextLong(2000, 8000))
                    // Now search
                    page.navigate(url)
                } catch (e: Exception) {
                    logger.error("Navigation failed for $keyword: ${e.message}")
                    throw ScraperTimeoutException("Initial navigation timed out.")
                }

                // Captcha/Consent Banner
                // if (handleConsent) {
                if (true)  {
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
        throw PermanentScrapingFailureException("Failed to scrape after 2 attempts: $keyword", e)
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
            // Delete the webdriver property so it doesn't even exist
            const newProto = Object.getPrototypeOf(navigator);
            delete newProto.webdriver;
            Object.setPrototypeOf(navigator, newProto);
            
            // Standard 2026 chrome overrides
            window.chrome = { runtime: {} };
            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
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
        repeat(Random.nextInt(1, 5)) {
            // Random Smooth Scroll
            // Using mouse.wheel is fine, but doing it in small increments mimics a finger/wheel better
            val scrollAmount = Random.nextDouble(200.0, 500.0)
            val steps = Random.nextInt(5, 10)
            for (i in 1..steps) {
                page.mouse().wheel(0.0, scrollAmount / steps)
                Thread.sleep(Random.nextLong(50, 150))
            }

            // 3. Random Mouse Move with "Jiggle"
            // Instead of one jump, we move to a target via an intermediate point
            val targetX = Random.nextDouble(100.0, 800.0)
            val targetY = Random.nextDouble(100.0, 600.0)
            page.mouse().move(targetX / 2, targetY / 2) // Intermediate
            Thread.sleep(Random.nextLong(5000, 30000))
            page.mouse().move(targetX, targetY)         // Final

            // 4. Random Pause (Thinking time)
            Thread.sleep(Random.nextLong(2000, 12000))
        }
    }

    /**
     * Wait for ACF/Magazine hydration
     * Handle common "Challenge/Bot" selectors when looking for results
     */
    private fun validatePageContent(page: Page, keyword: String) {
        try {
            // We wait for the BODY to exist as a bare minimum navigation signal.
            page.waitForSelector("body", Page.WaitForSelectorOptions().setTimeout(5000.0))

            // We look for ANY of these three success indicators:
            // - #b_results: The classic list
            // - #copans_container: The AI answer
            // - .b_algo: The class used for individual organic result items (most reliable)
            // - #challenge-form: To catch the block early
            val resultsSelectors = "#b_results, #copans_container, .b_algo"
            val challengeSelectors = "#challenge-form, .hCaptcha"
            val successSelectors = resultsSelectors + challengeSelectors

            logger.info("Waiting for results for: $keyword")
            page.waitForSelector(successSelectors, Page.WaitForSelectorOptions().setTimeout(10000.0))

            if (page.locator(challengeSelectors).isVisible) {
                throw ScraperBlockedException("CAPTCHA/Challenge detected.")
            }

            // Final verification of presence of results
            val hasResults = page.locator(resultsSelectors).count() > 0

            if (!hasResults) {
                // Log what we actually saw to stop guessing
                val title = page.title()
                logger.warn("No result containers found. Page title: $title")
                throw ScraperLayoutException("Results container missing on page: $title")
            }
        } catch (e: com.microsoft.playwright.TimeoutError) {
            val title = page.title()
            logger.error("TIMEOUT: Selector not found within 10s. Page title: '$title'")
            throw ScraperTimeoutException("Timed out waiting for results layout. Title: $title")
        }
    }

    private fun extractFunctionalLinks(page: Page): Map<String, Int> {
        var totalOrganic = 0
        var totalAds = 0

        val frames = page.frames()
        logger.info("Starting extraction across ${frames.size} frames.")

        frames.forEachIndexed { fIdx, frame ->
            try {
                val counts = frame.evaluate("""
            () => {
                const getAllLinks = (root) => {
                    let links = Array.from(root.querySelectorAll('a'));
                    try {
                        const shadowRoots = Array.from(root.querySelectorAll('*'))
                            .map(el => { try { return el.shadowRoot } catch(e) { return null } })
                            .filter(Boolean);
                        for (const shadow of shadowRoots) {
                            links = links.concat(getAllLinks(shadow));
                        }
                    } catch (e) { /* root restricted */ }
                    return links;
                };

                const links = getAllLinks(document);
                let organic = 0;
                let ads = 0;
                
                //if (links.length > 0) {
                    console.log('--- FRAME [' + (document.title || 'Untitled') + '] Candidates: ' + links.length);
                //}

                links.forEach((link) => {
                    try {
                        const href = link.href || link.getAttribute('data-href');
                        if (!href) return;
                        console.log('--- Link: ' + href.substring(0, 50) + ' ---')
                        if (href.startsWith('javascript:')) return;
                        
                        // Use base URI to handle relative links inside iframes correctly
                        const urlObj = new URL(href, document.baseURI);
                        
                        console.log('URL: ' + urlObj.href)
                        console.log('Pathname: ' + urlObj.pathname)
                        console.log('Search Params: ' + urlObj.searchParams.toString())
                        
                        if (urlObj.pathname.includes('/aclk') || urlObj.pathname.includes('/aclick')) {
                            ads++;
                            return;
                        }
            
                        if (urlObj.pathname.includes('/ck/a')) {
                            const uParam = urlObj.searchParams.get('u');
                            if (!uParam) return;
        
                            const isHttps = uParam.includes('aHR0cHM');
                            const isHttp = uParam.includes('aHR0cDov');
                            const isMaps = uParam.includes('L21hcHM');

                            if (isHttps || isHttp || isMaps) {
                                organic++;
                                if (organic <= 3) console.log('MATCH: ' + urlObj.searchParams.get('u').substring(0,10));
                            }
                        }
                    } catch (e) { /* link-level skip */ }
                });
            
                return { organic, ads };
            }
        """) as Map<String, Any>

                totalOrganic += (counts["organic"] as Number).toInt()
                totalAds += (counts["ads"] as Number).toInt()
            } catch (e: Exception) {
                logger.debug("Frame $fIdx inaccessible: ${e.message}")
            }
        }

        logger.info("FINAL AGGREGATED RESULT: Organic: $totalOrganic, Ads: $totalAds")
        return mapOf("organic" to totalOrganic, "ads" to totalAds)
    }

    private fun extractFunctionalLinks1(page: Page): Map<String, Int> {
        var totalOrganic = 0
        var totalAds = 0

        val frames = page.frames()
        logger.info("Starting extraction across ${frames.size} frames with Shadow DOM piercing.")

        frames.forEachIndexed { _, frame ->
            try {
                val counts = frame.evaluate("""
                () => {
                    // Helper to recurse through Shadow DOMs
                    const getAllLinks = (root) => {
                        let links = Array.from(root.querySelectorAll('a'));
                        const shadowRoots = Array.from(root.querySelectorAll('*'))
                            .map(el => el.shadowRoot)
                            .filter(Boolean);
                        for (const shadow of shadowRoots) {
                            links = links.concat(getAllLinks(shadow));
                        }
                        return links;
                    };

                    const links = getAllLinks(document);
                    let organic = 0;
                    let ads = 0;
                    
                    if (links.length > 0) {
                        console.log('--- FRAME [' + document.title + '] DEBUG (Found ' + links.length + ' candidates) ---');
                    }

                    links.forEach((link) => {
                        try {
                            const href = link.href || link.getAttribute('data-href');
                            
                            // Log links that normally die silently to debug blocking/filters
                            if (!href) return;
                            if (href.startsWith('javascript:')) {
                                console.log('DEBUG: JavaScript link skipped: ' + href.substring(0, 30));
                                return;
                            }
                            
                            const urlObj = new URL(href, window.location.origin);
                            
                            // 1. Ad Check
                            if (urlObj.pathname.includes('/aclk')) {
                                ads++;
                                console.log('AD FOUND: ' + href.substring(0, 100));
                                return;
                            }
                
                            // 2. Organic /ck/a Check
                            if (urlObj.pathname.includes('/ck/a')) {
                                const uParam = urlObj.searchParams.get('u');
                                if (!uParam) {
                                    console.log('ORGANIC SKIP: No "u" param found in ' + href.substring(0, 50));
                                    return;
                                }
            
                                const isHttps = uParam.startsWith('a1aHR0cHM');
                                const isHttp = uParam.startsWith('a1aHR0cDov');
                                const isMaps = uParam.startsWith('a1L21hcHM');

                                let decoded = 'decode-failed';
                                try {
                                    const base64Part = uParam.startsWith('a1') ? uParam.substring(2) : uParam;
                                    decoded = atob(base64Part.replace(/-/g, '+').replace(/_/g, '/'));
                                } catch(e) {}

                                if (isHttps || isHttp || isMaps) {
                                    organic++;
                                    console.log('ORGANIC MATCH [' + uParam.substring(0, 12) + '...]: ' + decoded);
                                } else {
                                    console.log('ORGANIC INTERNAL/OTHER [' + uParam.substring(0, 12) + '...]: ' + decoded);
                                }
                            }
                        } catch (e) { 
                            console.log('ERROR processing link: ' + e.message);
                        }
                    });
                
                    return { organic, ads };
                }
            """) as Map<String, Any>

                totalOrganic += (counts["organic"] as Number).toInt()
                totalAds += (counts["ads"] as Number).toInt()
            } catch (e: Exception) {
                // Skip cross-origin frames that block script execution
            }
        }

        logger.info("FINAL AGGREGATED RESULT: Organic: ${totalOrganic}, Ads: ${totalAds}")
        return mapOf("organic" to totalOrganic, "ads" to totalAds)
    }

    /**
     * Extract data about ads and organic search results from the page.
     */
}
