package com.scraper.api.service

import com.scraper.api.domain.user.AppUser
import com.scraper.api.domain.user.AppUserRepository
import com.scraper.api.web.dto.SignupRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val appUserRepository: AppUserRepository
) {

    @Transactional
    fun registerNewUser(request: SignupRequest): AppUser {
        // 1. Check if the user already exists in the database
        if (appUserRepository.existsById(request.firebaseUid)) {
            // TODO: Switch to custom exception instead of using the generic
            throw IllegalStateException("User with UID ${request.firebaseUid} already exists.")
        }

        // 2. Create the new AppUser entity
        val newUser = AppUser(
            firebaseUid = request.firebaseUid,
            email = request.email
        )

        // 3. Save the new user record to the PostgreSQL database
        return appUserRepository.save(newUser)
    }
}
