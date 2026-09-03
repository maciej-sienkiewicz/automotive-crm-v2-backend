package pl.detailing.crm.visitcard

import org.springframework.stereotype.Service
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    /**
     * Reason the send should NOT go out, or null. The card is one link for the whole
     * visit, so a link delivered at booking time already reached the customer; a second
     * delivery is legitimate only when the employee asked for it explicitly ([resend]).
     * The check is per requested channel: an SMS after an e-mail is not a duplicate.
     */
    fun blockReason(status: VisitCardSendStatus, channel: VisitCardDeliveryChannel?, resend: Boolean): String? {
        if (resend) return null
        val alreadyOnChannel = when (channel) {
            VisitCardDeliveryChannel.EMAIL -> status.lastEmailSentAt
            VisitCardDeliveryChannel.SMS -> status.lastSmsSentAt
            VisitCardDeliveryChannel.BOTH, null -> status.lastSmsSentAt ?: status.lastEmailSentAt
            else -> null
        } ?: return null
        val at = FORMAT.format(alreadyOnChannel.atZone(WARSAW))
        return "Karta została już wysłana $at. Potwierdź ponowną wysyłkę, jeśli klient ma dostać ją jeszcze raz."
    }

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

    private companion object {
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
