package com.scraper.worker.integration

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Paths

@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "runStealthCheck", matches = "true")
class StealthCanaryIT {

//    private val symlinkPath: String = Paths.get(System.getProperty("user.dir"), "worker-service/bin/patchright/chrome").toString()
    private val symlinkPath = "/Users/gp/scraper-backend/worker-service/bin/patchright/chrome"
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `verify Patchright binary bypasses bot detection`(headlessMode: Boolean) {
        // Resolve the internal binary path (Mac vs Linux)
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val fullExecutablePath = if (isMac) {
            Paths.get(symlinkPath, "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
        } else {
            Paths.get(symlinkPath, "chrome")
        }

        println("🚀 Launching Stealth Canary (Headless=$headlessMode) at: $fullExecutablePath")

        Playwright.create().use { playwright ->
            val browser = playwright.chromium().launch(
                BrowserType.LaunchOptions()
                    .setExecutablePath(fullExecutablePath)
                    .setHeadless(headlessMode)
                    .setArgs(listOf(
                        "--disable-blink-features=AutomationControlled", // Force override
                        "--no-sandbox"
                    ))
            )

            val page = browser.newPage()

            // Navigate to detection target
            page.navigate("https://www.browserscan.net/bot-detection")

            // Wait for detection suite to execute
            page.waitForTimeout(5000.0)

            // navigator.webdriver should always be false with Patchright
            val isWebdriver = page.evaluate("navigator.webdriver") as Boolean

            println("🔍 [Headless=$headlessMode] Stealth Result -> navigator.webdriver: $isWebdriver")
            Thread.sleep(25000)
            assertFalse(isWebdriver) {
                "❌ Stealth Check Failed for Headless=$headlessMode: navigator.webdriver is TRUE."
            }

            println("✅ Stealth Verified for Headless=$headlessMode.")
            browser.close()
        }
    }
}
