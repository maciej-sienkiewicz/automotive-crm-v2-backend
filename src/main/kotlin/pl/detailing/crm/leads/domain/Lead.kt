package pl.detailing.crm.leads.domain

import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
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
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val customerId: CustomerId?,
    val appointmentId: AppointmentId?,
    val visitId: VisitId?,
    val assignedUserId: UserId? = null,
    val assignedUserName: String? = null,
    val lostReason: String? = null,
    val stagnantAlertSentAt: Instant? = null,
    val newActivityAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
