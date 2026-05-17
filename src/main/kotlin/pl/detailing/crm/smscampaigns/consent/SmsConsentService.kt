package pl.detailing.crm.smscampaigns.consent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestStatus
import pl.detailing.crm.smscampaigns.provider.SmsProvider
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
)

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
    private val smsProvider: SmsProvider,
    private val smsConsentRequestRepository: SmsConsentRequestRepository,
    private val visitRepository: VisitRepository,
    private val communicationLogService: CommunicationLogService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(SmsConsentService::class.java)

        /**
         * Sentinel UUID used as `updatedBy` when an approval is triggered automatically
         * by a customer SMS reply (no authenticated system user is present).
         */
        val CUSTOMER_SMS_USER_ID: UserId = UserId(UUID(0L, 0L))
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
        changes: ServiceChangesSummary
    ) {
        val normalizedPhone = normalizePolishPhone(customerPhone)

        smsConsentRequestRepository.supersedePendingByVisitId(visitId.value)

        val message = buildConsentMessage(changes, proposedTotalGrossCents)

        val result = smsProvider.send(normalizedPhone, message)

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
        changes: ServiceChangesSummary
    ) {
        val normalizedPhone = normalizePolishPhone(customerPhone)
        val message = buildNotificationMessage(changes, totalGrossCents)
        val result = smsProvider.send(normalizedPhone, message)

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
    internal fun buildConsentMessage(changes: ServiceChangesSummary, totalGrossCents: Long): String {
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
        parts.add("Odpisz TAK aby zatwierdzic.")

        return parts.joinToString(" ")
    }

    /**
     * Builds the one-way notification SMS body — same change details as the consent
     * message but without the "Odpisz TAK" call-to-action.
     *
     * Example: "Dodano: Polerowanie. Lacznie: 450.00 PLN brutto."
     */
    internal fun buildNotificationMessage(changes: ServiceChangesSummary, totalGrossCents: Long): String {
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

    /** Joins up to [maxItems] names, appending "i inne" when the list is longer. */
    private fun List<String>.toShortenedList(maxItems: Int = 3): String =
        if (size <= maxItems) joinToString(", ")
        else take(maxItems).joinToString(", ") + " i inne"

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun formatGrossPrice(cents: Long): String {
        val whole = cents / 100
        val fraction = cents % 100
        return "%d.%02d".format(whole, fraction)
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
