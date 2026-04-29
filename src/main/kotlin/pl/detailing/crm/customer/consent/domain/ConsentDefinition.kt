package pl.detailing.crm.customer.consent.domain

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * A consent definition represents a persistent customer agreement (e.g., "RODO", "Marketing Consent").
 *
 * Unlike visit protocols that are generated fresh per visit, a consent is signed once per customer
 * and remains valid until revoked or a new version requiring re-sign is published.
 *
 * Display configuration (stage, isMandatory, displayOrder) controls when the consent
 * is surfaced during visit check-in/check-out if the customer lacks a valid signature.
 */
data class ConsentDefinition(
    val id: ConsentDefinitionId,
    val studioId: StudioId,
    val slug: String,
    val name: String,
    val description: String?,
    val stage: ProtocolStage,       // Which visit stage triggers this consent (CHECK_IN or CHECK_OUT)
    val isMandatory: Boolean,       // If true, the visit cannot proceed without a valid signature
    val displayOrder: Int,          // Ordering within a stage (lower = shown first)
    val isActive: Boolean,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)
