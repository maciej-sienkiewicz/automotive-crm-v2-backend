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

@JvmInline
value class ServiceId(val value: UUID) : Serializable {
    companion object {
        fun random() = ServiceId(UUID.randomUUID())
        fun fromString(value: String) = ServiceId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for Customer entities
 */
@JvmInline
value class CustomerId(val value: UUID) : Serializable {
    companion object {
        fun random() = CustomerId(UUID.randomUUID())
        fun fromString(value: String) = CustomerId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for Vehicle entities
 */
@JvmInline
value class VehicleId(val value: UUID) : Serializable {
    companion object {
        fun random() = VehicleId(UUID.randomUUID())
        fun fromString(value: String) = VehicleId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for Appointment entities
 */
@JvmInline
value class AppointmentId(val value: UUID) : Serializable {
    companion object {
        fun random() = AppointmentId(UUID.randomUUID())
        fun fromString(value: String) = AppointmentId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for AppointmentColor entities
 */
@JvmInline
value class AppointmentColorId(val value: UUID) : Serializable {
    companion object {
        fun random() = AppointmentColorId(UUID.randomUUID())
        fun fromString(value: String) = AppointmentColorId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Engine type for vehicles
 */
enum class EngineType {
    GASOLINE,
    DIESEL,
    HYBRID,
    ELECTRIC
}

/**
 * Vehicle status
 */
enum class VehicleStatus {
    ACTIVE,
    SOLD,
    ARCHIVED
}

/**
 * Ownership role for vehicle-customer relationship
 */
enum class OwnershipRole {
    PRIMARY,
    CO_OWNER,
    COMPANY
}

data class Money(
    val amountInCents: Long
) : Serializable {
    init {
        require(amountInCents >= 0) { "Money amount cannot be negative" }
    }

    fun plus(other: Money): Money = Money(amountInCents + other.amountInCents)
    fun minus(other: Money): Money = Money(amountInCents - other.amountInCents)
    fun times(multiplier: Int): Money = Money(amountInCents * multiplier)

    companion object {
        val ZERO = Money(0)
        fun fromCents(cents: Long) = Money(cents)
        fun fromAmount(amount: Double) = Money((amount * 100).toLong())
    }
}

enum class VatRate(val rate: Int) {
    VAT_23(23),
    VAT_8(8),
    VAT_5(5),
    VAT_0(0),
    VAT_ZW(-1);

    fun calculateVatAmount(netAmount: Money): Money {
        if (this == VAT_ZW) return Money.ZERO
        return Money((netAmount.amountInCents * rate) / 100)
    }

    fun calculateGrossAmount(netAmount: Money): Money {
        return netAmount.plus(calculateVatAmount(netAmount))
    }

    companion object {
        fun fromInt(value: Int): VatRate = entries.find { it.rate == value }
            ?: throw IllegalArgumentException("Invalid VAT rate: $value")
    }
}

/**
 * Base exception for business logic violations
 */
sealed class BusinessException(message: String) : RuntimeException(message)

class UnauthorizedException(message: String = "Unauthorized access") : BusinessException(message)
class ForbiddenException(message: String = "Access denied") : BusinessException(message)
class ValidationException(message: String) : BusinessException(message)
class EntityNotFoundException(message: String) : BusinessException(message)