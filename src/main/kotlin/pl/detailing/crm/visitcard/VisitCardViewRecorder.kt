package pl.detailing.crm.visitcard

import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditChannel
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.infrastructure.AuditFeedFilters
import pl.detailing.crm.audit.infrastructure.AuditFeedQueryRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visitcard.infrastructure.VisitCardTokenEntity
import java.time.Duration
import java.time.Instant

/**
 * Records that the customer opened their Visit Card.
 *
 * Worth having in the feed — it tells the studio the link arrived and was read, which is
 * the difference between "the customer has not replied" and "the customer never saw it".
 *
 * Collapsed to at most one entry per card per [VIEW_WINDOW]: a card is a web page the
 * customer refreshes, switches back to, and reopens from the SMS, and one row per HTTP GET
 * would bury every other event in the feed. The window is enforced by looking for a recent
 * entry for the same card rather than by keeping state, so it survives restarts and works
 * across instances.
 */
@Service
class VisitCardViewRecorder(
    private val auditService: AuditService,
    private val auditFeedQueryRepository: AuditFeedQueryRepository
) {

    fun recordView(token: VisitCardTokenEntity, card: VisitCardResponse) {
        val studioId = StudioId(token.studioId)
        val entityId = (token.visitId ?: token.appointmentId ?: return).toString()

        if (viewedRecently(studioId, entityId)) return

        val customerName = listOfNotNull(card.customer.firstName, card.customer.lastName)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

        auditService.recordSync(
            AuditEvent(
                studioId = studioId,
                actor = AuditActor.customer(null, customerName),
                module = AuditModule.VISIT_CARD,
                action = AuditAction.VISIT_CARD_OPENED,
                entityId = entityId,
                entityDisplayName = "Karta Wizyty ${card.visitNumber}",
                metadata = mapOf("visitStatus" to card.status),
                context = AuditContext(
                    customerName = customerName,
                    vehicleName = card.vehicle?.let { vehicle ->
                        listOfNotNull(vehicle.brand, vehicle.model, vehicle.licensePlate)
                            .joinToString(" ")
                            .takeIf { it.isNotBlank() }
                    },
                    visitId = token.visitId?.let { VisitId(it) },
                    visitName = card.vehicle?.let { vehicle ->
                        listOfNotNull(vehicle.brand, vehicle.model, vehicle.licensePlate)
                            .joinToString(" ")
                            .takeIf { it.isNotBlank() }
                    },
                    appointmentId = token.appointmentId?.let { AppointmentId(it) }
                ),
                channel = AuditChannel.PUBLIC_LINK
            )
        )
    }

    private fun viewedRecently(studioId: StudioId, entityId: String): Boolean =
        auditFeedQueryRepository.count(
            AuditFeedFilters(
                studioId = studioId.value,
                module = AuditModule.VISIT_CARD,
                entityId = entityId,
                actions = listOf(AuditAction.VISIT_CARD_OPENED),
                from = Instant.now().minus(VIEW_WINDOW)
            )
        ) > 0

    companion object {
        private val VIEW_WINDOW: Duration = Duration.ofMinutes(3)
    }
}
