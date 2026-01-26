package pl.detailing.crm.leads.domain

import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/**
 * Domain model for lead tracking
 * Represents potential customers from various sources (phone, email, manual entry)
 */
data class Lead(
    val id: LeadId,
    val studioId: StudioId,
    val source: LeadSource,
    val status: LeadStatus,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)
