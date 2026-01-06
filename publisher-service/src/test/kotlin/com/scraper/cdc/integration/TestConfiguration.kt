package com.scraper.cdc.integration

import com.google.api.gax.core.CredentialsProvider
import com.google.auth.Credentials
import com.google.cloud.pubsub.v1.Publisher
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.mockito.Mockito.*

@TestConfiguration
class TestConfig {
    @Bean
    @Primary
    fun mockPublisher(): Publisher {
        val mockPublisher = mock(Publisher::class.java)
        // Set up a default successful future so the service doesn't crash on .get()
        val future = com.google.api.core.ApiFutures.immediateFuture("test-msg-id")
        `when`(mockPublisher.publish(any())).thenReturn(future)
        return mockPublisher
    }

    @Bean
    fun googleCredentialsProvider(): CredentialsProvider {
        return CredentialsProvider { mock(Credentials::class.java) }
    }
}
