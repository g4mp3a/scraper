package com.scraper.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.scraper.api.domain.outbox.OutboxEventRepository
import com.scraper.api.domain.search.KeywordSearch
import com.scraper.api.domain.search.KeywordSearchRepository
import com.scraper.api.domain.user.AppUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.BDDMockito.given
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockMultipartFile
import org.springframework.data.domain.PageRequest
import java.io.ByteArrayInputStream
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class KeywordServiceTest {

    @Mock
    private lateinit var keywordSearchRepository: KeywordSearchRepository

    @Mock
    private lateinit var outboxEventRepository: OutboxEventRepository

    private lateinit var keywordService: KeywordService
    private val objectMapper = ObjectMapper()

    private val testUser = AppUser(
        firebaseUid = "test-user-123",
        email = "test@example.com"
    )

    @BeforeEach
    fun setUp() {
        keywordService = KeywordService(keywordSearchRepository, outboxEventRepository, objectMapper)
    }

    @Test
    fun `processKeywordFileUpload should save jobs and create outbox events atomically`() {
        // Arrange
        val csvContent = "search term a\nsearch term b"
        val file = MockMultipartFile("file", "keywords.csv", "text/csv", ByteArrayInputStream(csvContent.toByteArray()))

        // Objects we expect to be returned
        val savedJob1 = KeywordSearch(id = 1L, user = testUser, keyword = "search term a")
        val savedJob2 = KeywordSearch(id = 2L, user = testUser, keyword = "search term b")

        // Mock repository saving behavior
        given(keywordSearchRepository.save(any(KeywordSearch::class.java)))
            .willReturn(savedJob1, savedJob2)

        // Act
        val jobIds = keywordService.processKeywordFileUpload(file, testUser)

        // Assert
        assertEquals(2, jobIds.size)
        assertTrue(jobIds.containsAll(listOf(1L, 2L)))

        // Verify two KeywordSearch entities were saved
        verify(keywordSearchRepository, times(2)).save(any(KeywordSearch::class.java))

        // Verify two OutboxEvent entities were saved
        val eventCaptor: ArgumentCaptor<com.scraper.api.domain.outbox.OutboxEvent> = ArgumentCaptor.forClass(com.scraper.api.domain.outbox.OutboxEvent::class.java)
        verify(outboxEventRepository, times(2)).save(eventCaptor.capture())

        // Verify content of the captured outbox events
        val capturedEvents = eventCaptor.allValues
        assertTrue(capturedEvents.any { it.aggregateId == "1" && it.type == "KEYWORD_UPLOADED" })
        assertTrue(capturedEvents.any { it.aggregateId == "2" && it.type == "KEYWORD_UPLOADED" })
        assertTrue(capturedEvents.all { it.aggregateType == "KEYWORD_SEARCH" })
        assertTrue(capturedEvents.all { it.payload.has("searchId") && it.payload.has("userId") && it.payload.has("keyword") })
        assertTrue(capturedEvents.all { it.payload.get("userId").asText() == "test-user-123" })
        assertTrue(capturedEvents.all { it.payload.get("keyword").asText() in listOf("search term a", "search term b") })
    }

    @Test
    fun `getJobsByUserId should return list of jobs for user`() {
        // Arrange
        val jobs = listOf(
            KeywordSearch(id = 1L, user = testUser, keyword = "term a"),
            KeywordSearch(id = 2L, user = testUser, keyword = "term b")
        )
        given(keywordSearchRepository.findAllByUser_FirebaseUidOrderByCreatedAtDesc("test-user-123"))
            .willReturn(jobs)

        // Act
        val result = keywordService.getJobsByUserId("test-user-123")

        // Assert
        assertEquals(2, result.size)
        assertEquals("term a", result[0].keyword)
        verify(keywordSearchRepository).findAllByUser_FirebaseUidOrderByCreatedAtDesc("test-user-123")
    }

    @Test
    fun `getJobDetails should return specific job when authorized`() {
        // Arrange
        val job = KeywordSearch(id = 10L, user = testUser, keyword = "search term")
        given(keywordSearchRepository.findByIdAndUser_FirebaseUid(10L, "test-user-123"))
            .willReturn(Optional.of(job))

        // Act
        val result = keywordService.getJobDetails(10L, "test-user-123")

        // Assert
        assertEquals("search term", result?.keyword)
        verify(keywordSearchRepository).findByIdAndUser_FirebaseUid(10L, "test-user-123")
    }

    @Test
    fun `searchJobsByKeyword should return paginated results`() {
        // Arrange
        val query = "findme"
        val pageable = PageRequest.of(0, 10)
        val mockPage = org.springframework.data.domain.PageImpl(listOf(
            KeywordSearch(id = 1L, user = testUser, keyword = "findme now")
        ))

        given(keywordSearchRepository.findAllByUser_FirebaseUidAndContainingKeywordIgnoreCase("test-user-123", query, pageable))
            .willReturn(mockPage)

        // Act
        val result = keywordService.searchJobsByKeyword("test-user-123", query, 0, 10)

        // Assert
        assertEquals(1, result.totalElements)
        assertEquals("findme now", result.content[0].keyword)
    }
}
