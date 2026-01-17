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

/**
 * Type-safe ID wrapper for Visit entities
 */
@JvmInline
value class VisitId(val value: UUID) : Serializable {
    companion object {
        fun random() = VisitId(UUID.randomUUID())
        fun fromString(value: String) = VisitId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for VisitServiceItem entities
 */
@JvmInline
value class VisitServiceItemId(val value: UUID) : Serializable {
    companion object {
        fun random() = VisitServiceItemId(UUID.randomUUID())
        fun fromString(value: String) = VisitServiceItemId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Visit status lifecycle
 */
enum class VisitStatus {
    IN_PROGRESS,        // Work has started
    READY_FOR_PICKUP,   // All work completed, ready for pickup
    COMPLETED,          // Vehicle handed over to customer
    REJECTED,           // Visit rejected
    ARCHIVED            // Visit archived
}

/**
 * Service item status for granular tracking within a visit
 */
enum class VisitServiceStatus {
    PENDING,        // Additional work found, awaiting customer approval
    APPROVED,       // Approved for execution
    REJECTED,       // Customer declined the service
    CONFIRMED       // Confirmed and ready to execute (default status for initial services)
}

/**
 * Comment type for visit comments
 */
enum class CommentType {
    INTERNAL,       // Internal comment visible only to staff
    FOR_CUSTOMER    // Comment visible to customer
}

/**
 * Photo type for vehicle documentation
 */
enum class PhotoType {
    FRONT,
    REAR,
    LEFT_SIDE,
    RIGHT_SIDE,
    DAMAGE_FRONT,
    DAMAGE_REAR,
    DAMAGE_LEFT,
    DAMAGE_RIGHT,
    DAMAGE_OTHER
}

/**
 * Type-safe ID for photo upload sessions
 */
@JvmInline
value class PhotoUploadSessionId(val value: UUID) : Serializable {
    companion object {
        fun random() = PhotoUploadSessionId(UUID.randomUUID())
        fun fromString(value: String) = PhotoUploadSessionId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID for visit photos
 */
@JvmInline
value class VisitPhotoId(val value: UUID) : Serializable {
    companion object {
        fun random() = VisitPhotoId(UUID.randomUUID())
        fun fromString(value: String) = VisitPhotoId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID for visit journal entries
 */
@JvmInline
value class VisitJournalEntryId(val value: UUID) : Serializable {
    companion object {
        fun random() = VisitJournalEntryId(UUID.randomUUID())
        fun fromString(value: String) = VisitJournalEntryId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID for visit documents
 */
@JvmInline
value class VisitDocumentId(val value: UUID) : Serializable {
    companion object {
        fun random() = VisitDocumentId(UUID.randomUUID())
        fun fromString(value: String) = VisitDocumentId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID for visit comments
 */
@JvmInline
value class VisitCommentId(val value: UUID) : Serializable {
    companion object {
        fun random() = VisitCommentId(UUID.randomUUID())
        fun fromString(value: String) = VisitCommentId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID for visit comment revisions
 */
@JvmInline
value class VisitCommentRevisionId(val value: UUID) : Serializable {
    companion object {
        fun random() = VisitCommentRevisionId(UUID.randomUUID())
        fun fromString(value: String) = VisitCommentRevisionId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type of journal entry
 */
enum class JournalEntryType {
    INTERNAL_NOTE,              // Internal notes for team
    CUSTOMER_COMMUNICATION      // Communication with customer
}

/**
 * Type of document
 */
enum class DocumentType {
    PHOTO,      // Photo documentation
    PDF,        // PDF document
    PROTOCOL    // Protocol document
}

/**
 * Type-safe ID wrapper for ConsentDefinition entities
 */
@JvmInline
value class ConsentDefinitionId(val value: UUID) : Serializable {
    companion object {
        fun random() = ConsentDefinitionId(UUID.randomUUID())
        fun fromString(value: String) = ConsentDefinitionId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for ConsentTemplate entities
 */
@JvmInline
value class ConsentTemplateId(val value: UUID) : Serializable {
    companion object {
        fun random() = ConsentTemplateId(UUID.randomUUID())
        fun fromString(value: String) = ConsentTemplateId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Type-safe ID wrapper for CustomerConsent entities
 */
@JvmInline
value class CustomerConsentId(val value: UUID) : Serializable {
    companion object {
        fun random() = CustomerConsentId(UUID.randomUUID())
        fun fromString(value: String) = CustomerConsentId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Status of a customer's consent for a specific definition
 */
enum class ConsentStatus {
    VALID,      // Customer signed the current active version
    OUTDATED,   // Customer signed an older version, but new version doesn't require re-sign
    REQUIRED    // Customer never signed OR new version requires re-sign
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
class NotFoundException(message: String) : BusinessException(message)