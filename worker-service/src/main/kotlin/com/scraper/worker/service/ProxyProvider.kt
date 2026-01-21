package com.scraper.worker.service

import com.microsoft.playwright.options.Proxy
import org.springframework.stereotype.Service

interface ProxyProvider {
    /** @return A Playwright Proxy object or null for a direct connection */
    fun getNextProxy(): Proxy?
}

@Service
class DirectConnectionProxyProvider : ProxyProvider {
    override fun getNextProxy(): Proxy? = null
}
