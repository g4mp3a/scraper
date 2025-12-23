package com.scraper.api.integration

import com.google.firebase.auth.FirebaseAuth
import com.scraper.api.domain.user.AppUserRepository
import com.scraper.api.security.filter.FirebaseTokenFilter
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class TestSecurityConfig {

    /**
     * Provides a Mockito mock of FirebaseAuth.
     * We use @Primary to ensure this is the bean chosen if any other
     * FirebaseAuth beans exist in the context.
     */
    @Bean
    @Primary
    fun firebaseAuth(): FirebaseAuth {
        return Mockito.mock(FirebaseAuth::class.java)
    }

    /**
     * Manually defines the FirebaseTokenFilter bean.
     * Since the production class is annotated with @Profile("!test"),
     * Spring would normally ignore it during tests. This definition
     * forces the bean into the context for our Security tests.
     */
    @Bean
    fun firebaseTokenFilter(
        firebaseAuth: FirebaseAuth,
        appUserRepository: AppUserRepository
    ): FirebaseTokenFilter {
        return FirebaseTokenFilter(firebaseAuth, appUserRepository)
    }
}
