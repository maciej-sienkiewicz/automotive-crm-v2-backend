package pl.detailing.crm.communication

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId

/**
 * Links pre-visit communication entries to the visit created from the same appointment.
 *
 * Booking-confirmation and pre-visit reminder SMS are recorded with visitId = null because
 * the visit does not exist yet when those messages are sent.  This service is called once,
 * immediately after a reservation is converted to a visit, to fill in the missing visitId so
 * that all appointment-related SMS appear in GET /visits/{id}/communication.
 */
@Service
class AppointmentCommunicationLinker(
    private val repository: CommunicationLogJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // @Modifying queries require an active transaction; using REQUIRES_NEW so this
    // always has its own transaction regardless of the caller's coroutine thread context.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun linkToVisit(appointmentId: AppointmentId, visitId: VisitId, studioId: StudioId) {
        try {
            val updated = repository.linkAppointmentCommunicationToVisit(
                appointmentId = appointmentId.value,
                visitId = visitId.value,
                studioId = studioId.value
            )
            if (updated > 0) {
                logger.debug(
                    "Linked {} communication log entry(ies) [appointmentId={} -> visitId={}]",
                    updated, appointmentId, visitId
                )
            }
        } catch (ex: Exception) {
            logger.error(
                "Failed to link communication log entries [appointmentId={} visitId={}]: {}",
                appointmentId, visitId, ex.message, ex
            )
        }
    }
}
