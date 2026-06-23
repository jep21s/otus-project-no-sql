package com.example.orderservice.web

import com.example.orderservice.service.exception.ConflictException
import com.example.orderservice.service.exception.ForbiddenException
import com.example.orderservice.service.exception.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ErrorResponse(
    val status: Int,
    val message: String,
    val timestamp: Instant = Instant.now()
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(e: NotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(404, e.message ?: "Not found"))

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(e: ForbiddenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(403, e.message ?: "Forbidden"))

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(e: ConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(409, e.message ?: "Conflict"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val msg = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ErrorResponse(400, msg))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(400, e.message ?: "Bad request"))

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(500, e.message ?: "Internal server error"))
    }
}
