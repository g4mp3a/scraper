package com.scraper.worker.service

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import com.scraper.worker.dto.ScrapeResult
import com.scraper.worker.service.exception.*
import com.scraper.worker.util.BrowserActionUtils.randomDelay
import com.scraper.worker.util.BrowserActionUtils.clickConsentBanner
import com.scraper.worker.util.BrowserActionUtils.humanTyping
import com.scraper.worker.util.BrowserActionUtils.performHumanScroll
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Recover
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import java.net.URLEncoder

@Service
class BingScraper(
    @Value("\${scraper.bing.handle-consent:true}") private val handleConsent: Boolean,
    private val browserProvider: BrowserProviderTier3,
) {
    private val logger = LoggerFactory.getLogger(BingScraper::class.java)

    @Retryable(
        value = [ScraperTimeoutException::class, ScraperLayoutException::class],
        maxAttempts = 2,
        backoff = Backoff(delay = 15000, maxDelay = 45000, random = true)
    )
    fun scrape(keyword: String): ScrapeResult {

        return try {
            browserProvider.executeInNewPage { page ->
                // Browser stealth from patchwright
                // Dont block images and media

                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val url = "https://www.bing.com/search?q=$encodedKeyword"

                // Initial jitter
                randomDelay(2000, 5000)

                try {
                    // Warm the session
                    page.navigate("https://www.bing.com",
                        Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED))
                    page.bringToFront()
                    // Wait for a second or two
                    randomDelay(1000, 3000)
                    // Captcha/Consent Banner
                    if (handleConsent) {
                        clickConsentBanner(page)
                    }
                    // Type in the query and hit enter to search
                    humanTyping(page, "input[name='q']", keyword)
                    randomDelay(750, 2000)
                    page.keyboard().press("Enter")
                } catch (e: Exception) {
                    logger.error("Navigation failed for $keyword: ${e.message}")
                    throw ScraperTimeoutException("Initial navigation timed out.")
                }

                // Captcha/Consent Banner
                if (handleConsent) {
                    clickConsentBanner(page)
                }

                // Human behaviour
                performHumanScroll(page)

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

    /**
     * Extract data about ads and organic search results from the page.
     */
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
                                if (organic <= 3) console.log('MATCH: ' + urlObj.searchParams.get('u').substring(0,100));
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
}
