package com.scraper.worker.config

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KtorConfig {

    // Default 10 seconds for initial connection
    @Value("\${ktor.client.connect-timeout-ms:10000}")
    private val connectTimeoutMs: Long = 10000L

    // Default 30 seconds for the entire request/response (optimizing worker concurrency)
    @Value("\${ktor.client.request-timeout-ms:30000}")
    private val requestTimeoutMs: Long = 30000L

    @Value("\${ktor.client.max-connections:100}")
    private val maxConnections: Int = 100

    @Bean
    fun httpClient(): HttpClient {
        return HttpClient(CIO) {
            // 1. Global settings
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = connectTimeoutMs
                socketTimeoutMillis = requestTimeoutMs
            }

            // 2. Engine-specific settings
            engine {
                maxConnectionsCount = maxConnections
            }
        }
    }
}
