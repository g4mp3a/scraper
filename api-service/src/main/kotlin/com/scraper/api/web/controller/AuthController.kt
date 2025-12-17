package com.scraper.api.web.controller

import com.scraper.api.service.AuthService
import com.scraper.api.web.dto.SignupRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    /**
     * Endpoint for user registration.
     * The client/ui must first register with Firebase Auth, then call this endpoint to
     * register the Firebase UID and email in the scraper app database.
     * This endpoint is public (permitAll()) as defined in SecurityConfig.
     */
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<Map<String, String>> {
        return try {
            // 1. Register new user in the database
            val newUser = authService.registerNewUser(request)

            // 2. Return success response
            ResponseEntity.status(HttpStatus.CREATED).body(
                mapOf("message" to "User registered successfully", "userId" to newUser.firebaseUid)
            )
        } catch (e: IllegalStateException) {
            // TODO: Switch to custom exception instead of using the generic
            // Handle case where user already exists
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("error" to e.message.orEmpty())
            )
        } catch (e: Exception) {
            // Handle other unexpected errors
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                mapOf("error" to "Could not complete registration: ${e.message}")
            )
        }
    }

    // NOTE: A sign-in API is not required for JWT authentication
    // because the client handles sign-in with Firebase and the JWT is validated
    // by the FirebaseTokenFilter on subsequent requests.
}
