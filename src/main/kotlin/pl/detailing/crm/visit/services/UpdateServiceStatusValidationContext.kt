package pl.detailing.crm.visit.services

import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitServiceItem

/**
 * Validation context for updating service status
 */
data class UpdateServiceStatusValidationContext(
    val studioId: StudioId,
    val visitId: VisitId,
    val serviceItemId: VisitServiceItemId,
    val newStatus: VisitServiceStatus,
    val visit: Visit?,
    val serviceItem: VisitServiceItem?
)
