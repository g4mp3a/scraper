package com.scraper.worker.service

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.scraper.worker.service.exception.ScraperBlockedException
import com.scraper.worker.service.exception.ScraperLayoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.test.util.ReflectionTestUtils

class PlaywrightBingScraperTest {

    private lateinit var browserProvider: PlaywrightBrowserProvider
    private lateinit var deviceEmulator: DeviceEmulator
    private lateinit var scraper: PlaywrightBingScraper
    private lateinit var page: Page
    private lateinit var mouse: Mouse

    private fun <T> anyKotlin(cls: Class<T>): T {
        any<T>(cls)
        return when (cls) {
            DeviceProfile::class.java -> DeviceProfile("", 0, 0) as T
            Function1::class.java -> { { _: Page -> } as T }
            //java.util.function.Consumer::class.java -> java.util.function.Consumer { _: Any -> } as T
            java.util.function.Consumer::class.java -> java.util.function.Consumer { _: com.microsoft.playwright.Route -> } as T
            else -> null as T
        }
    }

    @BeforeEach
    fun setup() {
        browserProvider = mock(PlaywrightBrowserProvider::class.java)
        deviceEmulator = mock(DeviceEmulator::class.java)
        page = mock(Page::class.java)
        mouse = mock(Mouse::class.java)

        `when`(page.mouse()).thenReturn(mouse)
        `when`(deviceEmulator.getRandomDevice()).thenReturn(DeviceProfile("test-ua", 1280, 720))

        scraper = PlaywrightBingScraper(browserProvider, deviceEmulator)
        ReflectionTestUtils.setField(scraper, "handleConsent", false)

        `when`(page.content()).thenReturn("<html>test</html>")
        `when`(page.evaluate(anyString())).thenReturn(mapOf("organic" to 0, "ads" to 0))

        // FIX: Explicitly cast and provide classes to the matchers
        `when`(browserProvider.executeInNewPage<Any>(
            anyKotlin(DeviceProfile::class.java),
            anyKotlin(Function1::class.java) as (Page) -> Any
        )).thenAnswer { invocation ->
            val block = invocation.getArgument<(Page) -> Any>(1)
            block(page)
        }

    }

    @Test
    fun `should successfully scrape and return results`() {
        // Arrange
        setupStandardLocators(isBlocked = false, hasResults = true)
        `when`(page.evaluate(anyString())).thenReturn(mapOf("organic" to 5, "ads" to 2))

        // Act
        val result = scraper.scrape("kotlin testing")

        // Assert
        assertEquals(5, result.linkCount)
        assertEquals(2, result.adCount)
        verify(page).navigate(contains("q=kotlin+testing"))
    }

    @Test
    fun `should throw ScraperLayoutException when results container is missing`() {
        // Arrange
        setupStandardLocators(isBlocked = false, hasResults = false)

        // Act & Assert
        assertThrows<ScraperLayoutException> {
            scraper.scrape("failing keyword")
        }
    }

    @Test
    fun `validatePageContent should throw ScraperBlockedException when CAPTCHA is detected`() {
        // Arrange
        setupStandardLocators(isBlocked = true, hasResults = false)

        // Act & Assert
        assertThrows<ScraperBlockedException> {
            scraper.scrape("test-keyword")
        }
    }

    @Test
    fun `should apply stealth and block images before navigation`() {
        // Arrange
        setupStandardLocators(isBlocked = false, hasResults = true)

        // Act
        scraper.scrape("test")

        // Assert
        verify(page).addInitScript(contains("webdriver"))
        verify(page).route(contains("png"), anyKotlin(java.util.function.Consumer::class.java) as java.util.function.Consumer<com.microsoft.playwright.Route>)
    }

    @Test
    fun `should perform human-like actions including scrolling and mouse movement`() {
        // Arrange
        setupStandardLocators(isBlocked = false, hasResults = true)

        // Act
        scraper.scrape("human actions test")

        // Assert
        verify(mouse, atLeastOnce()).move(anyDouble(), anyDouble())
        verify(mouse, atLeastOnce()).wheel(anyDouble(), anyDouble())
        verify(page).waitForSelector("body")
    }

    // --- Helper to DRY up the locator mocking ---
    private fun setupStandardLocators(isBlocked: Boolean, hasResults: Boolean) {
        val resultsLocator = mock(Locator::class.java)
        val challengeLocator = mock(Locator::class.java)

        // The code now uses these specific combined strings
        val resultsSelectors = "#b_results, #copans_container, .b_algo"
        val challengeSelectors = "#challenge-form, .hCaptcha"

        // Mock the page to return these locators for the exact strings used in production
        `when`(page.locator(resultsSelectors)).thenReturn(resultsLocator)
        `when`(page.locator(challengeSelectors)).thenReturn(challengeLocator)

        // Mock the specific methods being called on those locators
        `when`(challengeLocator.isVisible).thenReturn(isBlocked)
        `when`(resultsLocator.isVisible).thenReturn(hasResults)

        // This is the line that was missing and caused the NPE
        `when`(resultsLocator.count()).thenReturn(if (hasResults) 1 else 0)
    }
}
