package pl.detailing.crm.shared

import java.io.Serializable
import java.util.*

/**
 * Type-safe ID wrapper for Studio/Company entities
 */
@JvmInline
value class StudioId(val value: UUID) : Serializable {
    companion object {
        fun random() = StudioId(UUID.randomUUID())
        fun fromString(value: String) = StudioId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for User entities
 */
@JvmInline
value class UserId(val value: UUID) : Serializable {
    companion object {
        fun random() = UserId(UUID.randomUUID())
        fun fromString(value: String) = UserId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * User roles within the multi-tenant system
 */
enum class UserRole {
    OWNER,      // Full access to studio data, team management, and billing
    MANAGER,    // Full operational access (visits, services, customers)
    DETAILER    // Limited access to assigned visits only
}

/**
 * Subscription status for studio billing
 */
enum class SubscriptionStatus {
    TRIALING,   // In free trial period
    ACTIVE,     // Active paid subscription
    PAST_DUE,   // Payment failed but still accessible
    EXPIRED     // Subscription ended, access blocked
}

/**
 * Base exception for business logic violations
 */
sealed class BusinessException(message: String) : RuntimeException(message)

class UnauthorizedException(message: String = "Unauthorized access") : BusinessException(message)
class ForbiddenException(message: String = "Access denied") : BusinessException(message)
class ValidationException(message: String) : BusinessException(message)
class EntityNotFoundException(message: String) : BusinessException(message)