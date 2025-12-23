package com.scraper.api.security.filter

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.scraper.api.domain.user.AppUser
import com.scraper.api.domain.user.AppUserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.context.annotation.Profile
import java.util.concurrent.ExecutionException

@Component
@Profile("!test")
class FirebaseTokenFilter(
    private val firebaseAuth: FirebaseAuth, // Injected from FirebaseConfig
    private val appUserRepository: AppUserRepository // Injected repository
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val BEARER_PREFIX = "Bearer "

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authorizationHeader = request.getHeader("Authorization")

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            // Pass unauthenticated request along. Spring Security will handle paths that require authentication.
            filterChain.doFilter(request, response)
            return
        }

        val token = authorizationHeader.substring(BEARER_PREFIX.length)

        try {
            // 2. Verify the Firebase ID Token (Network call)
            val firebaseToken: FirebaseToken = firebaseAuth.verifyIdTokenAsync(token).get()

            // 3. Check if the user exists in our local database
            val firebaseUid = firebaseToken.uid
            val appUser = appUserRepository.findById(firebaseUid).orElse(null)

            if (appUser == null) {
                // NOTE: If user is authenticated by Firebase but not registered locally (via signup flow)
                // We could throw an error or handle a registration step here. For now, we deny access.
                log.warn("Authenticated Firebase user not found in local DB: $firebaseUid")
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "User not registered.")
                return
            }

            // 4. Create and set the logged in user auth context in the security context
            val auth = FirebaseAuthenticationToken(appUser, firebaseToken)
            SecurityContextHolder.getContext().authentication = auth
            log.debug("Successfully authenticated Firebase user: ${appUser.email}")

        } catch (e: ExecutionException) {
            // Token verification specifically failed
            log.warn("Firebase verification failed: ${e.cause?.message}")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token.")
            return
        } catch (e: Exception) {
            // Something else went wrong (Database down, Firebase down, network issues etc.)
            log.error("Internal Auth Error: ${e.message}", e)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal authentication error.")
            return
        }

        // Continue the filter chain
        filterChain.doFilter(request, response)
    }
}

// Authentication object to hold the AppUser (for authorization checks later)
// This is what will be stored in the SecurityContext
class FirebaseAuthenticationToken(
    val appUser: AppUser,
    val firebaseToken: FirebaseToken
) : AbstractAuthenticationToken(
    appUser.getAuthorities()
) {
    override fun getCredentials(): Any = firebaseToken
    override fun getPrincipal(): Any = appUser
    override fun isAuthenticated(): Boolean = true
}

// AppUser.getAuthorities extension function
// The returned collection is used to tell Spring Security what the user is allowed to do aka their roles and permissions
// Keeping this simple for now
fun AppUser.getAuthorities(): Collection<GrantedAuthority> {
    // For the beta, we will grant a basic USER role to all authenticated users
    return listOf(SimpleGrantedAuthority("ROLE_USER"))
}
