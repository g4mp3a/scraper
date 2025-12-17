package com.scraper.api.web.controller

import com.scraper.api.domain.user.AppUser
import com.scraper.api.service.KeywordService
import com.scraper.api.web.dto.KeywordUploadResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/keywords")
class KeywordController(
    private val keywordService: KeywordService
) {

    /**
     * API to handle the upload of a CSV file containing search keywords.
     * Requires authentication (handled by FirebaseTokenFilter).
     * @AuthenticationPrincipal resolves the AppUser from the SecurityContext.
     */
    @PostMapping("/upload")
    fun uploadKeywords(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal user: AppUser // Injected from the FirebaseAuthenticationToken
    ): ResponseEntity<KeywordUploadResponse> {
        // Basic file validation
        if (file.isEmpty || !file.originalFilename.orEmpty().endsWith(".csv", ignoreCase = true)) {
            return ResponseEntity.badRequest().body(
                KeywordUploadResponse("Invalid file uploaded. Must be a non-empty CSV.", emptyList(), "REJECTED")
            )
        }

        return try {
            // 1. Process the file and publish events (atomically)
            val newJobIds = keywordService.processKeywordFileUpload(file, user)

            // 2. Return 202 ACCEPTED status for asynchronous processing
            ResponseEntity.status(HttpStatus.ACCEPTED).body(
                KeywordUploadResponse(
                    message = "Keyword jobs created and queued for processing.",
                    newJobIds = newJobIds
                )
            )
        } catch (e: IllegalArgumentException) {
            // TODO: Switch to a custom exception instead of using the generic
            ResponseEntity.badRequest().body(
                KeywordUploadResponse(e.message ?: "Validation failed.", emptyList(), "REJECTED")
            )
        } catch (e: Exception) {
            // Catch transaction failures, DB errors, etc.
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                KeywordUploadResponse("Failed to queue jobs: ${e.message}", emptyList(), "FAILED")
            )
        }
    }

    /**
     * API to get list of keywords
     * Used for the main results list view and the "Refresh" button.
     */
    @GetMapping
    fun getKeywordsList(
        @AuthenticationPrincipal user: AppUser
    ): ResponseEntity<List<KeywordJobDTO>> {
        val jobs = keywordService.getJobsByUserId(user.firebaseUid)
        val jobDTOs = jobs.map { KeywordJobDTO.fromEntity(it) }
        return ResponseEntity.ok(jobDTOs)
    }

    /**
     * API to get search result for each keyword
     * Fetches the detailed information, including the full HTML.
     */
    @GetMapping("/{id}")
    fun getKeywordDetails(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal user: AppUser
    ): ResponseEntity<KeywordDetailsDTO> {
        val job = keywordService.getJobDetails(id, user.firebaseUid)

        return if (job != null) {
            ResponseEntity.ok(KeywordDetailsDTO.fromEntity(job))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * API to search across all reports by keyword
     * Uses the 'q' parameter for the search query and supports pagination.
     */
    @GetMapping("/search")
    fun searchKeywords(
        @RequestParam("q") query: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal user: AppUser
    ): ResponseEntity<List<KeywordJobDTO>> {
        if (query.isBlank()) {
            return ResponseEntity.badRequest().build()
        }

        val kwSearchResultsPage = keywordService.searchJobsByKeyword(user.firebaseUid, query, page, size)
        val responseDTO = PageResponseDTO(
            content = kwSearchResultsPage.content.map { KeywordJobDTO.fromEntity(it) },
            totalElements = kwSearchResultsPage.totalElements,
            totalPages = kwSearchResultsPage.totalPages,
            currentPage = kwSearchResultsPage.number,
            pageSize = kwSearchResultsPage.size,
            isLast = kwSearchResultsPage.isLast
        )
        return ResponseEntity.ok(responseDTO)
    }
}
