package com.scraper.api.security.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.context.annotation.Profile

@Configuration
class FirebaseConfig(
    // ResourceLoader helps load files from the classpath (resources folder)
    private val resourceLoader: ResourceLoader
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    @Profile("!test")
    fun firebaseApp(): FirebaseApp {
        try {
            // TODO: For prod, change to have FirebaseOptions.builder() use GoogleCredentials from the IAM service account
            // or env property if that proves too challenging for the allowed time.
            // For now, load a file for local development
            val serviceAccount = resourceLoader.getResource("classpath:firebase-adminsdk.json").inputStream

            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build()

            logger.info("Firebase Admin SDK initialized successfully.")
            return FirebaseApp.initializeApp(options)

        } catch (e: Exception) {
            logger.error("Failed to initialize Firebase Admin SDK: ${e.message}", e)
            // Critical failure: the application cannot start securely without Firebase Auth
            throw RuntimeException("Failed to initialize Firebase Admin SDK.", e)
        }
    }

    // This bean is used by the JWT filter to verify tokens
    @Bean
    @Profile("!test")
    fun firebaseAuth(firebaseApp: FirebaseApp): FirebaseAuth {
        // Kotlin quirks for Firebase Admin Java SDK: This explicitly returns the FirebaseAuth object for the initialized FirebaseApp!
        return FirebaseAuth.getInstance(firebaseApp)
    }
}
