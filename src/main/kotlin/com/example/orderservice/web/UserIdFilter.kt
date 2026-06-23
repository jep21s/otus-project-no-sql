package com.example.orderservice.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Авторизация (исследовательский режим): каждый запрос обязан содержать валидный
 * заголовок X-User-Id. userId помещается в [RequestContext].
 */
@Component
class UserIdFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path.startsWith("/actuator") || path.startsWith("/error")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader(HEADER)
        if (header.isNullOrBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Missing X-User-Id header")
            return
        }
        val userId = try {
            UUID.fromString(header)
        } catch (e: IllegalArgumentException) {
            writeError(response, HttpStatus.BAD_REQUEST, "Invalid X-User-Id header")
            return
        }
        RequestContext.setUserId(userId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            RequestContext.clear()
        }
    }

    private fun writeError(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write("""{"status":${status.value()},"message":"$message"}""")
    }

    companion object {
        const val HEADER = "X-User-Id"
    }
}
