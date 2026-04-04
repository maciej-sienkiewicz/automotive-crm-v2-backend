package pl.detailing.crm.smscampaigns.consent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitServiceItemId
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
 * Handles 2-way SMS consent flow for service scope changes:
 *
 * 1. [sendConsentRequest] – called by [SaveVisitServicesHandler] when `notifyCustomer = true`.
 *    Sends an SMS to the customer asking them to reply "TAK" and stores a
 *    [SmsConsentRequestEntity] to track the pending approval.
 *
 * 2. [processInboundReply] – called by [SmsInboundController] when SMSAPI delivers an
 *    inbound message.  If the message is "TAK", the most recent PENDING consent request
 *    for that phone is found and all PENDING service items on the linked visit are approved.
 */
@Service
class SmsConsentService(
    private val smsProvider: SmsProvider,
    private val smsConsentRequestRepository: SmsConsentRequestRepository,
    private val visitRepository: VisitRepository
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
     * Any existing PENDING consent requests for the same visit are superseded first,
     * so a customer's most-recent "TAK" reply always maps to the latest scope change.
     *
     * This method participates in the caller's transaction – it does NOT open its own.
     * The SMS dispatch itself is a side-effect that happens before the DB commit;
     * callers should ensure the visit save completes before this is invoked.
     */
    @Transactional
    fun sendConsentRequest(
        visitId: VisitId,
        studioId: StudioId,
        customerPhone: String,
        proposedTotalGrossCents: Long
    ) {
        val normalizedPhone = normalizePolishPhone(customerPhone)

        // Supersede any open requests for this visit so the phone-lookup always
        // finds the latest one.
        smsConsentRequestRepository.supersedePendingByVisitId(visitId.value)

        val priceText = formatGrossPrice(proposedTotalGrossCents)
        val message = "Odpisz TAK jezeli wyrazasz zgode na zmiane zakresu uslugi. Nowa cena: $priceText PLN brutto."

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

        if (result.success) {
            logger.info(
                "Consent SMS sent | visit={} phone={} proposedGross={} externalId={}",
                visitId, normalizedPhone, priceText, result.externalMessageId
            )
        } else {
            logger.warn(
                "Consent SMS failed | visit={} phone={} error={}",
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

        // Force-load eager collection (guard against any lazy-loading edge cases)
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

        // Mark consent request as confirmed
        consentRequest.status = SmsConsentRequestStatus.CONFIRMED
        consentRequest.respondedAt = Instant.now()
        smsConsentRequestRepository.save(consentRequest)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Formats a gross price in cents/grosze to a human-readable PLN string,
     * e.g. 24600 → "246.00".
     */
    private fun formatGrossPrice(cents: Long): String {
        val whole = cents / 100
        val fraction = cents % 100
        return "%d.%02d".format(whole, fraction)
    }

    /**
     * Normalises a phone number arriving from SMSAPI inbound callback.
     * SMSAPI typically delivers numbers as "48XXXXXXXXX" (11 digits, no leading +).
     */
    private fun normalizeInboundPhone(phone: String): String {
        val cleaned = phone.replace(Regex("[^0-9+]"), "")
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.length == 11 && cleaned.startsWith("48") -> "+$cleaned"
            else -> normalizePolishPhone(cleaned)
        }
    }
}
