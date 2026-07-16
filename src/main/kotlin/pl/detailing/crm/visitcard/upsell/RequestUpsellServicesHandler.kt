package pl.detailing.crm.visitcard.upsell

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.PendingOperation
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.consent.SmsConsentService
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestStatus
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.VisitCardTokenService
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellReservationConsentEntity
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellReservationConsentRepository
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellReservationConsentStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellSuggestionStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import java.time.Instant
import java.util.UUID

/**
 * Handles the customer's "add suggested services" action from the public Visit Card.
 *
 * Both card flavours are supported:
 *
 *  - **Visit** (directly tokenized, or a reservation that has been checked in):
 *    selected suggestions become PENDING service items on the visit (same domain
 *    path as an employee-proposed scope change) and an [SmsConsentRequestEntity]
 *    is persisted so the existing inbound-reply webhook approves the items on "TAK".
 *
 *  - **Reservation** (appointment, pre-check-in): suggestions are marked REQUESTED
 *    and a [UpsellReservationConsentEntity] tracks the consent SMS; on "TAK",
 *    [ReservationUpsellConsentService] appends the services to the reservation's
 *    line items.
 *
 * In both cases the SMS reads:
 * "Odpisz TAK, żeby do rezerwacji dodać usługi: XXX (w cenie XXX PLN), …".
 */
@Service
class RequestUpsellServicesHandler(
    private val tokenService: VisitCardTokenService,
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val suggestionRepository: VisitUpsellSuggestionRepository,
    private val smsConsentRequestRepository: SmsConsentRequestRepository,
    private val reservationConsentRepository: UpsellReservationConsentRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(token: String, request: RequestUpsellServicesRequest): RequestUpsellServicesResponse {
        val tokenEntity = tokenService.findByToken(token)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")

        val studioId = StudioId(tokenEntity.studioId)

        val requestedIds = request.suggestionIds
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .toSet()
        if (requestedIds.isEmpty()) {
            throw ValidationException("Nie wybrano żadnych usług")
        }

        // Resolve the token to a visit (directly, or the visit created from the
        // tokenized reservation); fall back to the reservation itself.
        val visitEntity = when {
            tokenEntity.visitId != null ->
                visitRepository.findByIdAndStudioId(tokenEntity.visitId, studioId.value)
                    ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")
            else ->
                visitRepository.findByAppointmentIdAndStudioId(tokenEntity.appointmentId!!, studioId.value)
        }

        return if (visitEntity != null) {
            handleForVisit(visitEntity, studioId, requestedIds)
        } else {
            handleForReservation(AppointmentId(tokenEntity.appointmentId!!), studioId, requestedIds)
        }
    }

    // ── Visit flow ───────────────────────────────────────────────────────────

    private fun handleForVisit(
        visitEntity: VisitEntity,
        studioId: StudioId,
        requestedIds: Set<UUID>
    ): RequestUpsellServicesResponse {
        val visitId = VisitId(visitEntity.id)
        visitEntity.serviceItems.size // force-load within transaction

        val allSuggestions = suggestionRepository.findAllForVisitCard(
            visitEntity.id, visitEntity.appointmentId, studioId.value
        )
        val selected = allSuggestions.filter {
            it.id in requestedIds && it.status == UpsellSuggestionStatus.SUGGESTED
        }
        if (selected.isEmpty()) {
            throw ValidationException("Wybrane usługi nie są już dostępne do dodania")
        }

        val phone = requireCustomerPhone(visitEntity.customerId, studioId)

        // Pending service items — the exact snapshot frozen at suggestion time.
        val pendingItems = selected.map { suggestion ->
            VisitServiceItem.createPending(
                serviceId = ServiceId(suggestion.serviceId),
                serviceName = suggestion.serviceName,
                basePriceNet = Money.fromCents(suggestion.basePriceNet),
                vatRate = VatRate.fromInt(suggestion.vatRate),
                adjustmentType = suggestion.adjustmentType,
                adjustmentValue = suggestion.adjustmentValue,
                customNote = suggestion.note
            )
        }

        val updatedVisit = visitEntity.toDomain().saveServicesChanges(
            added = pendingItems,
            updated = emptyList(),
            deletedIds = emptyList(),
            updatedBy = SmsConsentService.CUSTOMER_SMS_USER_ID
        )
        visitRepository.save(VisitEntity.fromDomain(updatedVisit))

        val now = Instant.now()
        selected.forEachIndexed { index, suggestion ->
            suggestion.status = UpsellSuggestionStatus.REQUESTED
            suggestion.serviceItemId = pendingItems[index].id.value
            suggestion.requestedAt = now
        }
        suggestionRepository.saveAll(selected)

        val normalizedPhone = normalizePolishPhone(phone)
        val message = buildConsentSms(selected.map { it.serviceName to it.finalPriceGross })
        val (smsSent, errorMessage) = sendConsentSms(studioId, normalizedPhone, message)

        if (smsSent) {
            val proposedTotalGross = updatedVisit.serviceItems
                .filter { it.pendingOperation != PendingOperation.DELETE }
                .sumOf { it.finalPriceGross.amountInCents }
            smsConsentRequestRepository.supersedePendingByVisitId(visitId.value)
            smsConsentRequestRepository.save(
                SmsConsentRequestEntity(
                    id = UUID.randomUUID(),
                    visitId = visitId.value,
                    studioId = studioId.value,
                    customerPhone = normalizedPhone,
                    totalPriceGross = proposedTotalGross,
                    status = SmsConsentRequestStatus.PENDING,
                    externalMessageId = null,
                    createdAt = now,
                    respondedAt = null
                )
            )
        }

        recordCommunication(studioId, visitEntity.customerId, visitId, null, normalizedPhone, message, smsSent, errorMessage)

        logger.info(
            "Upsell requested from Visit Card | visit={} suggestions={} smsSent={}",
            visitId, selected.map { it.id }, smsSent
        )

        return buildResponse(smsSent, normalizedPhone) {
            suggestionRepository
                .findAllForVisitCard(visitEntity.id, visitEntity.appointmentId, studioId.value)
                .map { it.toPublicDto() }
        }
    }

    // ── Reservation (pre-check-in) flow ──────────────────────────────────────

    private fun handleForReservation(
        appointmentId: AppointmentId,
        studioId: StudioId,
        requestedIds: Set<UUID>
    ): RequestUpsellServicesResponse {
        val appointment = appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")

        val allSuggestions = suggestionRepository.findAllByAppointmentIdAndStudioId(appointmentId.value, studioId.value)
        val selected = allSuggestions.filter {
            it.id in requestedIds && it.status == UpsellSuggestionStatus.SUGGESTED
        }
        if (selected.isEmpty()) {
            throw ValidationException("Wybrane usługi nie są już dostępne do dodania")
        }

        val phone = requireCustomerPhone(appointment.customerId, studioId)

        val now = Instant.now()
        selected.forEach { suggestion ->
            suggestion.status = UpsellSuggestionStatus.REQUESTED
            suggestion.requestedAt = now
        }
        suggestionRepository.saveAll(selected)

        val normalizedPhone = normalizePolishPhone(phone)
        val message = buildConsentSms(selected.map { it.serviceName to it.finalPriceGross })
        val (smsSent, errorMessage) = sendConsentSms(studioId, normalizedPhone, message)

        if (smsSent) {
            reservationConsentRepository.supersedePendingByAppointmentId(appointmentId.value)
            reservationConsentRepository.save(
                UpsellReservationConsentEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    appointmentId = appointmentId.value,
                    customerPhone = normalizedPhone,
                    status = UpsellReservationConsentStatus.PENDING,
                    externalMessageId = null,
                    createdAt = now
                )
            )
        }

        recordCommunication(
            studioId, appointment.customerId, null, appointmentId, normalizedPhone, message, smsSent, errorMessage
        )

        logger.info(
            "Upsell requested from Reservation Card | appointment={} suggestions={} smsSent={}",
            appointmentId, selected.map { it.id }, smsSent
        )

        return buildResponse(smsSent, normalizedPhone) {
            suggestionRepository
                .findAllByAppointmentIdAndStudioId(appointmentId.value, studioId.value)
                .map { it.toPublicDto() }
        }
    }

    // ── Shared pieces ────────────────────────────────────────────────────────

    private fun requireCustomerPhone(customerId: UUID, studioId: StudioId): String =
        customerRepository.findByIdAndStudioId(customerId, studioId.value)
            ?.phone?.takeIf { it.isNotBlank() }
            ?: throw ValidationException(
                "Nie możemy wysłać SMS-a z potwierdzeniem — brak numeru telefonu. Skontaktuj się z serwisem."
            )

    /** @return smsSent to errorMessage */
    private fun sendConsentSms(studioId: StudioId, phone: String, message: String): Pair<Boolean, String?> =
        try {
            val result = communicationGateway.sendTransactionalSms(studioId.value, phone, message)
            result.success to result.errorMessage
        } catch (e: InsufficientSmsCreditsException) {
            logger.warn("Upsell consent SMS blocked — no credits [studio={}]", studioId)
            false to "Brak kredytów SMS"
        }

    private fun recordCommunication(
        studioId: StudioId,
        customerId: UUID,
        visitId: VisitId?,
        appointmentId: AppointmentId?,
        phone: String,
        message: String,
        smsSent: Boolean,
        errorMessage: String?
    ) {
        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = studioId,
                customerId = CustomerId(customerId),
                visitId = visitId,
                appointmentId = appointmentId,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.VISIT_CARD_UPSELL_SMS,
                recipientAddress = phone,
                subject = null,
                bodyContent = message,
                success = smsSent,
                errorMessage = errorMessage
            )
        )
    }

    private fun buildResponse(
        smsSent: Boolean,
        normalizedPhone: String,
        suggestionsProvider: () -> List<VisitCardUpsellSuggestion>
    ): RequestUpsellServicesResponse {
        val responseMessage = if (smsSent) {
            "Wysłaliśmy SMS na numer ${maskPhone(normalizedPhone)}. " +
                "Odpisz TAK, aby potwierdzić dodanie usług do rezerwacji."
        } else {
            "Zapisaliśmy Twój wybór, ale nie udało się wysłać SMS-a z potwierdzeniem. " +
                "Serwis skontaktuje się z Tobą w sprawie potwierdzenia."
        }
        return RequestUpsellServicesResponse(
            smsSent = smsSent,
            message = responseMessage,
            suggestions = suggestionsProvider()
        )
    }

    companion object {
        /**
         * Builds the consent SMS mandated by the business:
         * "Odpisz TAK, żeby do rezerwacji dodać usługi: XXX (w cenie XXX PLN), YYY (w cenie YYY PLN)"
         */
        internal fun buildConsentSms(services: List<Pair<String, Long>>): String {
            val list = services.joinToString(", ") { (name, grossCents) ->
                "$name (w cenie ${formatPln(grossCents)} PLN)"
            }
            return "Odpisz TAK, żeby do rezerwacji dodać usługi: $list"
        }

        private fun formatPln(cents: Long): String = "%d.%02d".format(cents / 100, cents % 100)

        /** +48100200300 → +48*****0300 — enough for the customer to recognise their number. */
        internal fun maskPhone(phone: String): String =
            if (phone.length <= 4) phone
            else phone.take(3) + "*".repeat(phone.length - 7) + phone.takeLast(4)
    }
}
