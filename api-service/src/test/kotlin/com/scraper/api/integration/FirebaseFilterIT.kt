package com.scraper.api.integration

import com.google.api.core.ApiFutures
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.scraper.api.domain.user.AppUser
import com.scraper.api.domain.user.AppUserRepository
import com.scraper.api.security.filter.FirebaseAuthenticationToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig::class)
class FirebaseFilterIT : BaseIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var firebaseAuth: FirebaseAuth

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Test
    @Transactional
    fun `filter should authenticate valid firebase token and allow file upload`() {
        // 1. Arrange
        val mockTokenStr = "valid-firebase-jwt-token"
        val mockUid = "firebase-user-123"
        val mockEmail = "test@example.com"

        val appUser = AppUser(firebaseUid = mockUid, email = mockEmail)
        appUserRepository.saveAndFlush(appUser)


        val mockFirebaseToken = mock(FirebaseToken::class.java)
        `when`(mockFirebaseToken.uid).thenReturn(mockUid)
        `when`(mockFirebaseToken.email).thenReturn(mockEmail)

        `when`(firebaseAuth.verifyIdTokenAsync(mockTokenStr))
            .thenReturn(ApiFutures.immediateFuture(mockFirebaseToken))

        // Create a dummy CSV file to satisfy the Controller's @RequestParam
        val file = MockMultipartFile(
            "file",
            "test.csv",
            "text/csv",
            "keyword1\nkeyword2".toByteArray()
        )

        // 2. Act & Assert
        mockMvc.perform(
            multipart("/api/keywords/upload")
                .file(file)
                .header("Authorization", "Bearer $mockTokenStr")
        )
            .andDo(print())
            .andExpect(status().isAccepted)
            // Assertions on the JSON response body
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.message").value("Keyword jobs created and queued for processing."))
            .andExpect(jsonPath("$.newJobIds").isArray())
            .andExpect(jsonPath("$.newJobIds.length()").value(2))
            .andExpect(jsonPath("$.newJobIds[0]").isNumber())    // Confirms it's an ID, regardless of value
            .andExpect(jsonPath("$.newJobIds[1]").isNumber())

        // Clean up context after test (redundant coz spring will clean it up when request processing completes; but good to be defensive
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `filter should return 401 when token is invalid`() {
        val badToken = "invalid-token"

        // Mock a failure in the Firebase SDK
        `when`(firebaseAuth.verifyIdTokenAsync(badToken))
            .thenReturn(ApiFutures.immediateFailedFuture(RuntimeException("Invalid Token")))

        mockMvc.perform(
            post("/api/keywords/upload")
                .header("Authorization", "Bearer $badToken")
        )
            .andDo(print())
            .andExpect(status().isUnauthorized)
    }
}
