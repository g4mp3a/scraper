package com.scraper.api.domain.user

import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, String> {
    // Spring Data JPA provides findById, save, etc.
}