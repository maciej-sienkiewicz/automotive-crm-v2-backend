package pl.detailing.crm.visitcard

import org.springframework.stereotype.Service
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import java.time.Instant
import java.util.UUID

/** When (if ever) the card link was successfully delivered, per channel. */
data class VisitCardSendStatus(
    val lastEmailSentAt: Instant?,
    val lastSmsSentAt: Instant?
)

/**
 * Answers "has this card already been sent to the customer?" from the
 * communication log, so the employee gets a clear signal before re-sending.
 * Covers both the visit and the reservation it originated from — a link sent
 * at booking time counts as sent for the visit too (it is the same link).
 */
@Service
class VisitCardSendStatusService(
    private val communicationLogRepository: CommunicationLogJpaRepository
) {
    private val cardMessageTypes = listOf(
        CommunicationMessageType.VISIT_CARD_EMAIL,
        CommunicationMessageType.VISIT_CARD_SMS
    )

    fun status(studioId: UUID, visitId: UUID?, appointmentId: UUID?): VisitCardSendStatus {
        val sends = communicationLogRepository.findSentByTypesForVisitOrAppointment(
            studioId = studioId,
            visitId = visitId,
            appointmentId = appointmentId,
            messageTypes = cardMessageTypes,
            status = CommunicationStatus.SENT
        )
        return VisitCardSendStatus(
            lastEmailSentAt = sends.firstOrNull { it.channel == CommunicationChannel.EMAIL }?.sentAt,
            lastSmsSentAt = sends.firstOrNull { it.channel == CommunicationChannel.SMS }?.sentAt
        )
    }
}
