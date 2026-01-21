package com.scraper.worker.service

import org.springframework.stereotype.Component
import kotlin.random.Random

data class DeviceProfile(val userAgent: String, val width: Int, val height: Int)

@Component
class DeviceEmulator {

 private val profiles = listOf(
        // --- DESKTOPS ---
        // Windows 11 - Chrome 133
        DeviceProfile("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36", 1920, 1080),
        // Standard PC - Chrome 133
        DeviceProfile("Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36", 1366, 768),
        // macOS Sonoma - Chrome 133
        DeviceProfile("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36", 1440, 900),

        // --- LAPTOPS ---
        // Linux Workstation - Chrome 133
        DeviceProfile("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36", 1536, 864),
        // High-end MacBook - Chrome 133
        DeviceProfile("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36", 1680, 1050),

        // --- TABLETS ---
        // Galaxy Tab S9 (Fixed Version to 133)
        DeviceProfile("Mozilla/5.0 (Linux; Android 14; SM-X910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36", 1024, 1600),

        // --- MOBILE ---
        // Pixel 8 - Chrome 133 Mobile
        DeviceProfile("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36", 412, 915),
        // Galaxy S23 - Chrome 133 Mobile
        DeviceProfile("Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36", 360, 780)
    )

    fun getRandomDevice(): DeviceProfile = profiles[Random.nextInt(profiles.size)]
}
