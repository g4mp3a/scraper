package com.scraper.worker.config

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KtorConfig {

    // Default 10 seconds for initial connection
    @Value("\${ktor.client.connect-timeout-ms:10000}")
    private val connectTimeoutMs: Int = 10000

    // Default 30 seconds for the entire request/response (optimizing worker concurrency)
    @Value("\${ktor.client.request-timeout-ms:30000}")
    private val requestTimeoutMs: Int = 30000

    @Value("\${ktor.client.max-connections:100}")
    private val maxConnections: Int = 100

    @Bean
    fun httpClient(): HttpClient {
        return HttpClient(CIO) {
            engine {
                connectTimeout = connectTimeoutMs
                requestTimeout = requestTimeoutMs
                maxConnectionsCount = maxConnections
            }
        }
    }
}
