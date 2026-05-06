package pl.detailing.crm.trends.exception

import org.springframework.http.HttpStatus

open class DataForSeoApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class DataForSeoAuthException(
    message: String = "DataForSEO authentication failed — verify credentials.",
    cause: Throwable? = null
) : DataForSeoApiException(message, HttpStatus.UNAUTHORIZED.value(), cause)

class TrendsRateLimitException(
    message: String = "DataForSEO rate limit exceeded (HTTP 429).",
    statusCode: Int? = null,
    cause: Throwable? = null
) : DataForSeoApiException(message, statusCode, cause)

class TrendsValidationException(message: String) : RuntimeException(message)
