package pl.detailing.crm.appointment.events

import pl.detailing.crm.shared.LeadStatus
import java.util.UUID

/**
 * Published when an appointment status changes in a way that should be reflected
 * on the linked lead (if any). The listener in the leads module handles the sync.
 *
 * targetLeadStatus mapping:
 *   ABANDONED / CANCELLED → NO_SHOW
 *   CREATED (restored)    → CONFIRMED
 *   CONVERTED             → COMPLETED  (visitId is set)
 */
data class AppointmentLeadSyncEvent(
    val appointmentId: UUID,
    val studioId: UUID,
    val targetLeadStatus: LeadStatus,
    val visitId: UUID? = null,
    val initiatorUserId: UUID,
    val initiatorDisplayName: String
)
