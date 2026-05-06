package com.example.demo.trends.exception

import org.springframework.http.HttpStatus

/**
 * Base exception for DataForSEO API errors.
 */
open class DataForSeoApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Thrown when the DataForSEO API returns a rate-limit response (HTTP 429 or status 40200).
 */
class TrendsRateLimitException(
    message: String = "DataForSEO rate limit exceeded",
    statusCode: Int? = null,
    cause: Throwable? = null
) : DataForSeoApiException(message, statusCode, cause)

/**
 * Thrown when input validation fails (bad keywords, bad date range, etc.).
 */
class TrendsValidationException(
    message: String
) : RuntimeException(message)

/**
 * Thrown when authentication with DataForSEO fails (HTTP 401/403).
 */
class DataForSeoAuthException(
    message: String = "DataForSEO authentication failed. Check credentials.",
    cause: Throwable? = null
) : DataForSeoApiException(message, HttpStatus.UNAUTHORIZED.value(), cause)

