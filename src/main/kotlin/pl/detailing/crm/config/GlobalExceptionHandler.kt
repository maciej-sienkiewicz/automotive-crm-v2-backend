// src/main/kotlin/pl/detailing/crm/config/GlobalExceptionHandler.kt

package pl.detailing.crm.config

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.detailing.crm.shared.*
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(ex: AuthenticationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                error = "Unauthorized",
                message = ex.message ?: "Authentication required",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                error = "Unauthorized",
                message = ex.message ?: "Authentication required",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(
                error = "Forbidden",
                message = ex.message ?: "Access denied",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                error = "Validation Error",
                message = ex.message ?: "Invalid request",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(EntityNotFoundException::class)
    fun handleNotFound(ex: EntityNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                error = "Not Found",
                message = ex.message ?: "Resource not found",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        ex.printStackTrace()
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                error = "Internal Server Error",
                message = "An unexpected error occurred: ${ex.message}",
                timestamp = Instant.now().toString()
            ))
    }
}

data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String
)