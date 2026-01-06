package com.scraper.worker.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // Critical for Pub/Sub POST requests
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/pubsub/push").permitAll()
                auth.anyRequest().authenticated()
            }
        return http.build()
    }
}
