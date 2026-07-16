package pl.detailing.crm.visitcard.upsell

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellReservationConsentRepository
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellReservationConsentStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellSuggestionStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import java.time.Instant

/**
 * Handles the customer's "TAK" reply for upsell requests made on a reservation
 * card (no visit exists yet, so the shared visit-consent flow does not apply).
 *
 * On confirmation the REQUESTED suggestions of the appointment become
 * CONFIRMED and are appended to the reservation's line items — they will be
 * carried into the visit at check-in like any other reserved service.
 */
@Service
class ReservationUpsellConsentService(
    private val consentRepository: UpsellReservationConsentRepository,
    private val suggestionRepository: VisitUpsellSuggestionRepository,
    private val appointmentRepository: AppointmentRepository,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun processInboundReply(rawPhone: String, messageText: String) {
        if (!messageText.trim().uppercase().startsWith("TAK")) return

        val normalizedPhone = normalizeInboundPhone(rawPhone)

        val consent = consentRepository
            .findTopByCustomerPhoneAndStatusOrderByCreatedAtDesc(normalizedPhone, UpsellReservationConsentStatus.PENDING)
            ?: return

        val appointment = appointmentRepository.findByIdAndStudioId(consent.appointmentId, consent.studioId)
            ?: run {
                logger.error(
                    "Reservation upsell consent points to a missing appointment | appointment={} studio={}",
                    consent.appointmentId, consent.studioId
                )
                return
            }

        val requested = suggestionRepository.findAllByAppointmentIdAndStudioIdAndStatus(
            consent.appointmentId, consent.studioId, UpsellSuggestionStatus.REQUESTED
        )

        val now = Instant.now()
        if (requested.isNotEmpty()) {
            requested.forEach { suggestion ->
                appointment.lineItems.add(
                    AppointmentLineItemEntity(
                        appointment = appointment,
                        serviceId = suggestion.serviceId,
                        serviceName = suggestion.serviceName,
                        basePriceNet = suggestion.basePriceNet,
                        vatRate = suggestion.vatRate,
                        adjustmentType = suggestion.adjustmentType,
                        adjustmentValue = suggestion.adjustmentValue,
                        finalPriceNet = suggestion.finalPriceNet,
                        finalPriceGross = suggestion.finalPriceGross,
                        customNote = suggestion.note
                    )
                )
                suggestion.status = UpsellSuggestionStatus.CONFIRMED
                suggestion.confirmedAt = now
            }
            appointment.updatedAt = now
            appointmentRepository.save(appointment)
            suggestionRepository.saveAll(requested)
        }

        consent.status = UpsellReservationConsentStatus.CONFIRMED
        consent.respondedAt = now
        consentRepository.save(consent)

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = pl.detailing.crm.shared.StudioId(consent.studioId),
                customerId = CustomerId(appointment.customerId),
                visitId = null,
                appointmentId = AppointmentId(consent.appointmentId),
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.SMS_INBOUND_REPLY,
                recipientAddress = normalizedPhone,
                subject = null,
                bodyContent = messageText,
                success = true,
                errorMessage = null,
                status = CommunicationStatus.RECEIVED
            )
        )

        logger.info(
            "Inbound TAK confirmed reservation upsell | appointment={} services={}",
            consent.appointmentId, requested.map { it.serviceName }
        )
    }

    private fun normalizeInboundPhone(phone: String): String {
        val cleaned = phone.replace(Regex("[^0-9+]"), "")
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.length == 11 && cleaned.startsWith("48") -> "+$cleaned"
            else -> normalizePolishPhone(cleaned)
        }
    }
}
