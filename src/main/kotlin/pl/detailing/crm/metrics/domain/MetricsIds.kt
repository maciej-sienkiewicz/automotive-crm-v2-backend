package pl.detailing.crm.metrics.domain

import java.io.Serializable
import java.util.UUID

/**
 * Type-safe identifiers for the metrics module.
 *
 * Consistent with the rest of the system: every identifier is a `@JvmInline value class`
 * so a `UserSessionId` can never be passed where an `ErrorEventId` is expected.
 */
@JvmInline
value class MetricEventId(val value: UUID) : Serializable {
    companion object {
        fun random() = MetricEventId(UUID.randomUUID())
        fun fromString(value: String) = MetricEventId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class UserSessionId(val value: UUID) : Serializable {
    companion object {
        fun random() = UserSessionId(UUID.randomUUID())
        fun fromString(value: String) = UserSessionId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class ApiEndpointId(val value: UUID) : Serializable {
    companion object {
        fun random() = ApiEndpointId(UUID.randomUUID())
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class ErrorEventId(val value: UUID) : Serializable {
    companion object {
        fun random() = ErrorEventId(UUID.randomUUID())
    }

    override fun toString(): String = value.toString()
}

/**
 * Stable hash identifying a class of errors (not a single occurrence).
 * See [pl.detailing.crm.metrics.errors.ErrorFingerprinter] for how it is derived.
 */
@JvmInline
value class ErrorFingerprint(val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "Fingerprint nie może być pusty" }
    }

    override fun toString(): String = value
}
