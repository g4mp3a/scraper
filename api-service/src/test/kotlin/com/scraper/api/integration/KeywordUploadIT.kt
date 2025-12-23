package com.scraper.api.integration

import com.google.firebase.auth.FirebaseToken
import com.google.firebase.auth.FirebaseAuth
import com.scraper.api.domain.outbox.OutboxEventRepository
import com.scraper.api.domain.search.KeywordSearchRepository
import com.scraper.api.domain.user.AppUserRepository
import com.scraper.api.domain.user.AppUser
import com.scraper.api.security.filter.FirebaseAuthenticationToken
import com.scraper.api.security.filter.FirebaseTokenFilter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import org.springframework.boot.test.mock.mockito.MockBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KeywordUploadIT : BaseIntegrationTest() {

    @MockBean
    private lateinit var firebaseAuth: FirebaseAuth // Satisfies the filter dependency
    @MockBean
    private lateinit var firebaseTokenFilter: FirebaseTokenFilter

    @Autowired private lateinit var keywordSearchRepository: KeywordSearchRepository
    @Autowired private lateinit var outboxEventRepository: OutboxEventRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        // No security filters applied here to avoid the Builder/Visibility nightmare
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
    }

    @Test
    @Transactional
    fun `POST to keywords-upload must create KeywordSearch and OutboxEvent`() {
        // 1. Arrange: Setup User and Auth Token
        val testUser = AppUser(firebaseUid = "test-uid", email = "int@test.com")
        appUserRepository.saveAndFlush(testUser)

        val mockFirebaseToken = mock(FirebaseToken::class.java)
        val auth = FirebaseAuthenticationToken(testUser, mockFirebaseToken)

        // Manually set the SecurityContext for this thread
        SecurityContextHolder.getContext().authentication = auth

        val csvContent = "search term 1"
        val file = MockMultipartFile("file", "test.csv", "text/csv", csvContent.toByteArray())

        // 2. Act
        mockMvc.perform(
            multipart("/api/keywords/upload")
                .file(file)
                .principal(auth) // Pass the actual auth object as the principal
        )
            .andExpect(status().isAccepted)

        // 3. Assert
        // Fetch only searches belonging to our specific test user
        val searches = keywordSearchRepository.findByUser_FirebaseUid(testUser.firebaseUid)
        assertEquals(1, searches.size, "Should find exactly 1 search for this user")

        val mySearch = searches[0]

        // Fetch only the outbox event linked to this specific search
        val events = outboxEventRepository.findByAggregateId(mySearch.id.toString())

        assertEquals(1, events.size, "Should find exactly 1 outbox event for the specific search ID")
        assertEquals("KEYWORD_UPLOADED", events[0].type)
        // Clean up context after test
        SecurityContextHolder.clearContext()
    }
}
