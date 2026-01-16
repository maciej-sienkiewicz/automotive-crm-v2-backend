package pl.detailing.crm.visit.services

import pl.detailing.crm.service.domain.Service
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit

/**
 * Validation context for adding service to visit
 */
data class AddServiceValidationContext(
    val studioId: StudioId,
    val visitId: VisitId,
    val serviceId: ServiceId,
    val visit: Visit?,
    val service: Service?
)
