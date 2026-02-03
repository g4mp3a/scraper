package com.scraper.worker.util

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import kotlin.random.Random
import org.slf4j.LoggerFactory

object BrowserActionUtils {
    private val logger = LoggerFactory.getLogger(BrowserActionUtils::class.java)

    fun randomDelay(min: Long, max: Long) = Thread.sleep(Random.nextLong(min, max))

    /**
     * Mimics a user reading a page.
     * Jittered scrolling and random pauses.
     */
    fun performHumanScroll(page: Page) {
        page.waitForSelector("body")
        repeat(Random.nextInt(2, 5)) {
            val scrollAmount = Random.nextDouble(300.0, 700.0)
            val steps = Random.nextInt(8, 15)
            for (i in 1..steps) {
                page.mouse().wheel(0.0, scrollAmount / steps)
                randomDelay(40, 400)
            }
            randomDelay(1000, 4000)
        }
    }

    /**
     * Mimics human typing with variable speed and occasional backspacing for errors.
     */
    fun humanTyping(page: Page, selector: String, text: String) {
        page.focus(selector)

        for (char in text) {
            // X% chance of a "typo" and correction
            val errorPercent = listOf(5, 10, 15).random()
            if (Random.nextInt(100) < errorPercent) {
                val typo = ('a'..'z').random()
                page.keyboard().type(typo.toString())
                randomDelay(100, 300)
                page.keyboard().press("Backspace")
            }

            page.keyboard().type(char.toString())
            // Random delay between keystrokes (mimics non-mechanical rhythm)
            randomDelay(80, 250)
        }
        randomDelay(500, 1500)
    }

    /**
     * In many regions (especially if Cloud Run instance is in Europe/Asia),
     * Bing will throw a massive modal blocking the results until you "Accept All."
     * If that banner is up, page.evaluate might still work, but NETWORKIDLE will take forever,
     * and some results might be hidden.
     */
    fun clickConsentBanner(page: Page) {
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
     * Identifies if we are stuck at a Cloudflare "Just a moment" or Turnstile page.
     */
    fun isCloudflareChallenge(page: Page): Boolean {
        val title = page.title()
        val content = page.content()
        val hasChallengeForm = try {
            page.locator("#challenge-form").isVisible
        } catch (e: Exception) { false }

        return title.contains("Just a moment") ||
                content.contains("Checking your browser") ||
                content.contains("cloudflare") ||
                hasChallengeForm
    }

    /**
     * Waits for the Turnstile challenge to resolve itself (Technical Stealth).
     * If Patchright is doing its job, Cloudflare often auto-passes after 2-5 seconds.
     */
    fun handleTurnstile(page: Page) {
        val turnstileFrame = page.frameLocator("iframe[src*='challenges.cloudflare.com']")
        try {
            logger.info("Cloudflare Turnstile detected. Waiting for auto-resolve...")
            // Use the correct options type for locators
            val waitOptions = Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(30000.0)
            turnstileFrame.locator("#challenge-stage").waitFor(waitOptions)
            logger.info("Turnstile challenge passed.")
            randomDelay(1000, 3000)
        } catch (e: Exception) {
            logger.warn("Turnstile auto-resolve failed.")
        }
    }
}
