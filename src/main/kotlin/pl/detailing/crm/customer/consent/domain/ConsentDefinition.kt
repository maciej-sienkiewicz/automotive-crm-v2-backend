package pl.detailing.crm.customer.consent.domain

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * A consent definition represents a type of document that customers must sign
 * (e.g., "RODO", "Marketing Consent").
 *
 * Each definition can have multiple versions (ConsentTemplate entities),
 * but only one version is active at a time.
 */
data class ConsentDefinition(
    val id: ConsentDefinitionId,
    val studioId: StudioId,
    val slug: String,               // Unique identifier within studio (e.g., "rodo", "marketing")
    val name: String,               // Display name (e.g., "RODO Privacy Policy")
    val description: String?,       // Optional description for admin reference
    val isActive: Boolean,          // Whether this definition is currently in use
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)
