package pl.detailing.crm.smscampaigns.consent

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestStatus
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Summary of what changed in the service list — used to build a human-readable consent SMS.
 * All three lists may be non-empty simultaneously when a single save mixes operations.
 */
data class ServiceChangesSummary(
    val addedNames: List<String>,
    val removedNames: List<String>,
    val priceChangedNames: List<String>
) {
    /** False for a save that changed nothing — there is nothing to tell the customer about. */
    val hasChanges: Boolean get() = addedNames.isNotEmpty() || removedNames.isNotEmpty() || priceChangedNames.isNotEmpty()
}

/**
 * Handles 2-way SMS consent flow for service scope changes:
 *
 * 1. [sendConsentRequest] – called by [SaveVisitServicesHandler] when `notifyCustomer = true`.
 *    Sends an SMS to the customer describing exactly what changed and asks them to reply "TAK".
 *
 * 2. [processInboundReply] – called by [SmsInboundController] when SMSAPI delivers an
 *    inbound message. If the message is "TAK", all PENDING service items on the linked
 *    visit are approved in a single transaction.
 */
@Service
class SmsConsentService(
    private val gateway: OutboundCommunicationGateway,
    private val smsConsentRequestRepository: SmsConsentRequestRepository,
    private val visitRepository: VisitRepository,
    private val communicationLogService: CommunicationLogService,
    private val eventPublisher: ApplicationEventPublisher
) {

    /**
     * Customer SMS goes through the gateway like every other one: module check, credits,
     * sender name and — when the studio switched it on — redirect to the studio's own phone.
     * Missing credits used to be invisible here (the provider was called directly); now the
     * request is simply recorded as failed with a readable reason.
     */
    private fun dispatch(studioId: StudioId, phone: String, message: String): SmsDeliveryResult = try {
        gateway.sendTransactionalSms(studioId.value, phone, message)
    } catch (e: InsufficientSmsCreditsException) {
        logger.warn("Service-change SMS blocked — no credits for studio={}", studioId.value)
        SmsDeliveryResult.failure("Brak kredytów SMS")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SmsConsentService::class.java)

        /**
         * Sentinel UUID used as `updatedBy` when an approval is triggered automatically
         * by a customer SMS reply (no authenticated system user is present).
         */
        val CUSTOMER_SMS_USER_ID: UserId = UserId(UUID(0L, 0L))

        /**
         * Wezwanie do odpowiedzi doklejane na końcu KAŻDEGO SMS-a o zmianie zakresu usług.
         *
         * Doklejamy je tutaj, przy wysyłce, a nie w treści proponowanej użytkownikowi CRM-a —
         * dzięki temu nie da się go usunąć ani edytować z poziomu interfejsu.
         */
        const val CONSENT_CALL_TO_ACTION = "Odpisz TAK aby zaakceptować."

        private val POLISH_TO_ASCII = mapOf(
            'ą' to "a", 'ć' to "c", 'ę' to "e", 'ł' to "l", 'ń' to "n",
            'ó' to "o", 'ś' to "s", 'ź' to "z", 'ż' to "z",
            'Ą' to "A", 'Ć' to "C", 'Ę' to "E", 'Ł' to "L", 'Ń' to "N",
            'Ó' to "O", 'Ś' to "S", 'Ź' to "Z", 'Ż' to "Z"
        )

        /**
         * Zamienia polskie znaki na ASCII. Bez tego każdy SMS idzie w UCS-2
         * (70 znaków na segment zamiast 160), co realnie podnosi koszt wysyłki.
         */
        fun toAscii(text: String): String =
            text.map { POLISH_TO_ASCII[it] ?: it.toString() }.joinToString("")

        /**
         * Deterministyczna treść zmian, używana gdy użytkownik nie podał własnej
         * (albo gdy nie udało się wygenerować propozycji przez LLM). Bez wezwania do odpowiedzi.
         */
        fun buildFallbackBody(changes: ServiceChangesSummary, totalGrossCents: Long): String {
            val parts = mutableListOf<String>()

            if (changes.addedNames.isNotEmpty()) {
                parts.add("Dodano: ${changes.addedNames.toShortenedList()}.")
            }
            if (changes.removedNames.isNotEmpty()) {
                parts.add("Usunieto: ${changes.removedNames.toShortenedList()}.")
            }
            if (changes.priceChangedNames.isNotEmpty()) {
                parts.add("Zmiana ceny: ${changes.priceChangedNames.toShortenedList()}.")
            }

            parts.add("Lacznie: ${formatGrossPrice(totalGrossCents)} PLN brutto.")

            return parts.joinToString(" ")
        }

        /**
         * Skleja treść wiadomości: własna treść z CRM-a (jeśli jest) lub szablon awaryjny.
         *
         * [appendCta] doklejamy tylko wtedy, gdy klient ma odpowiedzieć "TAK" — czyli
         * przy `requireConfirmation = true`. Przy samej notyfikacji (bez potwierdzenia)
         * nie ma na co odpisywać, więc frazy tam nie ma. Ewentualna fraza wpisana ręcznie
         * przez użytkownika (z ogonkami albo bez) jest zawsze ucinana, żeby nie wysłać jej
         * dwa razy ani nie zostawić martwego wezwania w wiadomości informacyjnej.
         */
        internal fun composeMessage(
            customMessage: String?,
            changes: ServiceChangesSummary,
            totalGrossCents: Long,
            usePolishCharacters: Boolean = false,
            appendCta: Boolean = true
        ): String {
            val body = customMessage?.trim()?.takeIf { it.isNotBlank() }
                ?: buildFallbackBody(changes, totalGrossCents)

            val withoutCta = listOf(
                CONSENT_CALL_TO_ACTION,
                CONSENT_CALL_TO_ACTION.removeSuffix("."),
                toAscii(CONSENT_CALL_TO_ACTION),
                toAscii(CONSENT_CALL_TO_ACTION).removeSuffix(".")
            ).fold(body.trim()) { acc, cta -> acc.removeSuffix(cta).trim() }

            val full = when {
                !appendCta -> withoutCta
                withoutCta.isEmpty() -> CONSENT_CALL_TO_ACTION
                else -> "$withoutCta $CONSENT_CALL_TO_ACTION"
            }

            return if (usePolishCharacters) full else toAscii(full)
        }

        /** Skleja do [maxItems] nazw, dopisując "i inne" gdy lista jest dłuższa. */
        private fun List<String>.toShortenedList(maxItems: Int = 3): String =
            if (size <= maxItems) joinToString(", ")
            else take(maxItems).joinToString(", ") + " i inne"

        internal fun formatGrossPrice(cents: Long): String {
            val whole = cents / 100
            val fraction = cents % 100
            return "%d.%02d".format(whole, fraction)
        }
    }

    /**
     * Sends a consent-request SMS to the customer and persists a tracking record.
     *
     * The message body is built dynamically from [changes] so the customer sees exactly
     * which services were added, removed, or had their price adjusted — rather than just
     * a total price with no context.
     *
     * Any existing PENDING consent requests for the same visit are superseded first,
     * so a customer's most-recent "TAK" reply always maps to the latest scope change.
     *
     * This method participates in the caller's transaction — it does NOT open its own.
     */
    @Transactional
    fun sendConsentRequest(
        visitId: VisitId,
        studioId: StudioId,
        customerPhone: String,
        proposedTotalGrossCents: Long,
        changes: ServiceChangesSummary,
        customMessage: String? = null,
        usePolishCharacters: Boolean = false
    ) {
        val normalizedPhone = normalizePolishPhone(customerPhone)

        smsConsentRequestRepository.supersedePendingByVisitId(visitId.value)

        val message = composeMessage(customMessage, changes, proposedTotalGrossCents, usePolishCharacters, appendCta = true)

        val result = dispatch(studioId, normalizedPhone, message)

        smsConsentRequestRepository.save(
            SmsConsentRequestEntity(
                id = UUID.randomUUID(),
                visitId = visitId.value,
                studioId = studioId.value,
                customerPhone = normalizedPhone,
                totalPriceGross = proposedTotalGrossCents,
                status = SmsConsentRequestStatus.PENDING,
                externalMessageId = result.externalMessageId,
                createdAt = Instant.now(),
                respondedAt = null
            )
        )

        val customerId = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)?.customerId
        if (customerId != null) {
            communicationLogService.record(
                RecordCommunicationCommand(
                    studioId = studioId,
                    customerId = CustomerId(customerId),
                    visitId = visitId,
                    channel = CommunicationChannel.SMS,
                    messageType = CommunicationMessageType.SMS_CONSENT_REQUEST,
                    recipientAddress = normalizedPhone,
                    subject = null,
                    bodyContent = message,
                    success = result.success,
                    errorMessage = result.errorMessage
                )
            )
        }

        if (result.success) {
            logger.info(
                "Consent SMS sent | visit={} phone={} proposedGross={} externalId={}",
                visitId, normalizedPhone, formatGrossPrice(proposedTotalGrossCents), result.externalMessageId
            )
        } else {
            logger.warn(
                "Consent SMS failed | visit={} phone={} error={}",
                visitId, normalizedPhone, result.errorMessage
            )
        }
    }

    /**
     * Sends a one-way informational SMS to the customer describing what changed in
     * the service list and the new total. No reply is expected or tracked —
     * no [SmsConsentRequestEntity] is created and pending items are NOT auto-approved.
     *
     * Used when `requireConfirmation = false` in the services-change payload.
     */
    @Transactional
    fun sendServiceChangeNotification(
        visitId: VisitId,
        studioId: StudioId,
        customerPhone: String,
        totalGrossCents: Long,
        changes: ServiceChangesSummary,
        customMessage: String? = null,
        usePolishCharacters: Boolean = false
    ) {
        val normalizedPhone = normalizePolishPhone(customerPhone)
        val message = composeMessage(customMessage, changes, totalGrossCents, usePolishCharacters, appendCta = false)
        val result = dispatch(studioId, normalizedPhone, message)

        val customerId = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)?.customerId
        if (customerId != null) {
            communicationLogService.record(
                RecordCommunicationCommand(
                    studioId = studioId,
                    customerId = CustomerId(customerId),
                    visitId = visitId,
                    channel = CommunicationChannel.SMS,
                    messageType = CommunicationMessageType.SMS_SERVICE_CHANGE_NOTIFICATION,
                    recipientAddress = normalizedPhone,
                    subject = null,
                    bodyContent = message,
                    success = result.success,
                    errorMessage = result.errorMessage
                )
            )
        }

        if (result.success) {
            logger.info(
                "Service-change notification SMS sent | visit={} phone={} totalGross={}",
                visitId, normalizedPhone, formatGrossPrice(totalGrossCents)
            )
        } else {
            logger.warn(
                "Service-change notification SMS failed | visit={} phone={} error={}",
                visitId, normalizedPhone, result.errorMessage
            )
        }
    }

    /**
     * Processes an inbound SMS reply from SMSAPI.
     *
     * If the message body starts with "TAK" (case-insensitive), the most recent PENDING
     * consent request for the sender's phone is looked up and all PENDING service items
     * on the linked visit are approved in a single transaction.
     */
    @Transactional
    fun processInboundReply(rawPhone: String, messageText: String) {
        if (!messageText.trim().uppercase().startsWith("TAK")) {
            logger.debug("Inbound SMS from {} ignored (not 'TAK'): {}", rawPhone, messageText)
            return
        }

        val normalizedPhone = normalizeInboundPhone(rawPhone)

        val consentRequest = smsConsentRequestRepository
            .findTopByCustomerPhoneAndStatusOrderByCreatedAtDesc(normalizedPhone, SmsConsentRequestStatus.PENDING)
            ?: run {
                logger.warn("Inbound TAK from {} – no PENDING consent request found", normalizedPhone)
                return
            }

        val visitId = VisitId(consentRequest.visitId)
        val studioId = StudioId(consentRequest.studioId)

        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: run {
                logger.error("Consent visit not found | visitId={} studioId={}", visitId, studioId)
                return
            }

        visitEntity.serviceItems.size

        val visit = visitEntity.toDomain()
        val pendingItems = visit.getPendingServices()

        if (pendingItems.isEmpty()) {
            logger.info("Inbound TAK from {} – visit {} has no pending services, marking consent confirmed anyway", normalizedPhone, visitId)
        } else {
            var updatedVisit = visit
            pendingItems.forEach { item ->
                updatedVisit = updatedVisit.approveService(item.id, CUSTOMER_SMS_USER_ID)
            }

            visitRepository.save(VisitEntity.fromDomain(updatedVisit))

            logger.info(
                "Inbound TAK from {} – approved {} pending service(s) on visit {}",
                normalizedPhone, pendingItems.size, visitId
            )
        }

        consentRequest.status = SmsConsentRequestStatus.CONFIRMED
        consentRequest.respondedAt = Instant.now()
        smsConsentRequestRepository.save(consentRequest)

        // Same-transaction event so dependent modules (e.g. Visit Card upselling)
        // can react to the confirmation atomically with the approval itself.
        eventPublisher.publishEvent(
            SmsConsentConfirmedEvent(
                visitId = visitId.value,
                studioId = studioId.value,
                approvedServiceItemIds = pendingItems.map { it.id.value }
            )
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = studioId,
                customerId = CustomerId(visitEntity.customerId),
                visitId = visitId,
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
    }

    // -------------------------------------------------------------------------
    // Message building
    // -------------------------------------------------------------------------

    /**
     * Builds the consent SMS body from the change summary and the proposed total.
     *
     * Examples of generated messages:
     *
     *   "Dodano: Polerowanie, Renowacja tapicerki. Lacznie: 450.00 PLN brutto. Odpisz TAK aby zatwierdzic."
     *
     *   "Dodano: Polerowanie. Usunieto: Mycie zewnetrzne. Lacznie: 320.00 PLN brutto. Odpisz TAK aby zatwierdzic."
     *
     *   "Zmiana ceny: Zabezpieczenie lakieru. Lacznie: 580.00 PLN brutto. Odpisz TAK aby zatwierdzic."
     *
     * Service name lists are capped at 3 items per section to keep the SMS concise;
     * additional items are summarised as "i inne".
     */
    internal fun buildConsentMessage(changes: ServiceChangesSummary, totalGrossCents: Long): String =
        composeMessage(null, changes, totalGrossCents, appendCta = true)

    internal fun buildNotificationMessage(changes: ServiceChangesSummary, totalGrossCents: Long): String =
        composeMessage(null, changes, totalGrossCents, appendCta = false)

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun normalizeInboundPhone(phone: String): String {
        val cleaned = phone.replace(Regex("[^0-9+]"), "")
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.length == 11 && cleaned.startsWith("48") -> "+$cleaned"
            else -> normalizePolishPhone(cleaned)
        }
    }
}
