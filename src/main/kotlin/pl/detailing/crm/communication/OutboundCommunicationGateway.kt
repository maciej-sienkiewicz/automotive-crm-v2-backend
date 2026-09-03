package pl.detailing.crm.communication

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.domain.MessageChannel
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.smscredits.SmsCreditService
import pl.detailing.crm.smscampaigns.sendername.SmsSenderNameResolver
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import java.util.UUID

/**
 * Single infrastructure-level gateway for all outbound communication.
 *
 * Every SMS and email in the system MUST go through this gateway — never via
 * SmsProvider or EmailProvider directly from application code.
 *
 * Responsibilities enforced here for free, for every caller:
 *   1. Module entitlement check — the "point of effect" (W1) enforcement layer.
 *      Sends are blocked when the studio has not purchased the module matching the
 *      message's [OutboundMessageCategory]. This catches every path the REST layer
 *      cannot see: schedulers, campaign engines, event-driven sends. A blocked send
 *      returns a failure result (never throws), so background dispatchers mark the
 *      item FAILED once instead of retrying forever; interactive paths are expected
 *      to be stopped earlier with HTTP 402 by @RequiresCapability.
 *   2. Marketing consent check — campaigns only. A campaign is blocked when the customer has
 *      not signed the required marketing consent; transactional messages are not marketing and
 *      are never gated by it (see [marketingConsentBlocked]).
 *   3. SMS credit check — blocked if the studio has no available credits.
 *      Credits are deducted atomically (SELECT FOR UPDATE) before the send attempt.
 *      If the provider call fails, the credit is refunded automatically.
 *   4. Delegating to the actual transport provider.
 *
 * Because all four checks live here, new send paths automatically inherit them
 * without any extra effort from the developer — and there is no way to bypass them.
 */
@Service
class OutboundCommunicationGateway(
    private val smsProvider: SmsProvider,
    private val emailProvider: EmailProvider,
    private val consentChecker: MarketingConsentChecker,
    private val smsCreditService: SmsCreditService,
    private val senderNameResolver: SmsSenderNameResolver,
    private val capabilityService: CapabilityService,
    private val meterRegistry: MeterRegistry,
    private val businessEventPublisher: BusinessEventPublisher
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Null means the studio has no SMSAPI-confirmed sender ID — the provider then sends
     * the message as ECO (from an SMSAPI number), never under a placeholder header.
     */
    private fun resolveSmsSenderName(studioId: UUID): String? = senderNameResolver.resolve(studioId)

    /**
     * Zgoda marketingowa dotyczy marketingu — czyli wyłącznie kampanii.
     *
     * [MarketingConsentChecker] sprawdza podpisaną zgodę marketingową, a nie prawo do kontaktu
     * w ogóle. Wiadomość transakcyjna (przypomnienie o wizycie, gotowość do odbioru, karta wizyty,
     * link do podpisu) jest wykonaniem usługi, o którą klient sam poprosił, i nie jest marketingiem.
     * Sprawdzanie jej po zgodzie marketingowej blokowało wysyłki komunikatem „Brak zgody na
     * komunikację SMS" u każdego studia, które w ogóle zdefiniowało zgodę marketingową — wystarczyło,
     * że klient jej nie podpisał, żeby przestał dostawać powiadomienia o własnej wizycie.
     *
     * Bramkę wyznacza więc [OutboundMessageCategory], a nie to, którą metodę wywołał kod:
     * `CAMPAIGN` wymaga zgody, wszystko inne nie. Ta sama reguła obowiązuje w obu kanałach —
     * transakcyjny mail nie staje się marketingiem przez to, że jest mailem.
     */
    private fun marketingConsentBlocked(
        customerId: UUID,
        studioId: UUID,
        channel: MarketingChannel,
        category: OutboundMessageCategory,
        context: String
    ): Boolean {
        if (category != OutboundMessageCategory.CAMPAIGN) return false
        return !consentChecker.canSend(customerId, studioId, channel, context.ifBlank { "OutboundGateway" })
    }

    /**
     * Live metrics — liczymy tylko wysyłki, które naprawdę wyszły do dostawcy.
     * Blokady (brak modułu, brak zgody, brak kredytów) wracają wcześniej i celowo nie liczą się
     * jako wysłana wiadomość; od tego są osobne liczniki `communication.blocked.*`.
     */
    private fun countSent(studioId: UUID, channel: MessageChannel, context: String) {
        businessEventPublisher.publish(
            tenantId = StudioId(studioId),
            type = BusinessEventType.MESSAGE_SENT,
            dimensionValue = channel.name,
            attributes = mapOf("context" to context)
        )
    }

    /**
     * Returns a human-readable block reason when the studio lacks the module for
     * this message category; null when the send may proceed. Every block is counted
     * (`communication.blocked.module`) — a non-zero rate on this metric means some
     * UI or scheduler path attempted a send it should have been gated away from.
     */
    private fun moduleBlockReason(studioId: UUID, category: OutboundMessageCategory): String? {
        val capability = category.requiredCapability
        if (capabilityService.hasCapability(StudioId(studioId), capability)) return null

        meterRegistry.counter(
            "communication.blocked.module",
            "category", category.name, "capability", capability.name
        ).increment()
        logger.warn(
            "Outbound {} blocked — capability {} not entitled for studio={}",
            category, capability, studioId
        )
        return "Moduł '${capability.displayName}' nie jest aktywny w tym studiu — wiadomość zablokowana"
    }

    fun sendSms(
        customerId: UUID,
        studioId: UUID,
        phoneNumber: String,
        message: String,
        context: String = "",
        category: OutboundMessageCategory = OutboundMessageCategory.TRANSACTIONAL
    ): SmsDeliveryResult {
        moduleBlockReason(studioId, category)?.let { return SmsDeliveryResult.failure(it) }

        if (marketingConsentBlocked(customerId, studioId, MarketingChannel.SMS, category, context)) {
            return SmsDeliveryResult.failure("Brak zgody na komunikację SMS")
        }

        val creditDeducted = smsCreditService.tryDeductCredit(StudioId(studioId))
        if (!creditDeducted) {
            logger.warn("SMS blocked — insufficient credits for studio={}", studioId)
            throw InsufficientSmsCreditsException("Brak kredytów SMS. Doładuj konto w panelu zarządzania.")
        }

        val senderName = resolveSmsSenderName(studioId)
        val result = smsProvider.send(phoneNumber, message, senderName)

        if (!result.success) {
            smsCreditService.refundCredit(StudioId(studioId), "Błąd dostawcy SMS: ${result.errorMessage}")
        } else {
            countSent(studioId, MessageChannel.SMS, context.ifBlank { category.name })
        }

        return result
    }

    fun sendTransactionalSms(
        studioId: UUID,
        phoneNumber: String,
        message: String,
        category: OutboundMessageCategory = OutboundMessageCategory.TRANSACTIONAL
    ): SmsDeliveryResult {
        moduleBlockReason(studioId, category)?.let { return SmsDeliveryResult.failure(it) }

        val creditDeducted = smsCreditService.tryDeductCredit(StudioId(studioId))
        if (!creditDeducted) {
            logger.warn("Transactional SMS blocked — insufficient credits for studio={}", studioId)
            throw InsufficientSmsCreditsException("Brak kredytów SMS. Doładuj konto w panelu zarządzania.")
        }

        val senderName = resolveSmsSenderName(studioId)
        val result = smsProvider.send(phoneNumber, message, senderName)

        if (!result.success) {
            smsCreditService.refundCredit(StudioId(studioId), "Błąd dostawcy SMS: ${result.errorMessage}")
        } else {
            countSent(studioId, MessageChannel.SMS, category.name)
        }

        return result
    }

    fun sendEmail(
        customerId: UUID,
        studioId: UUID,
        to: String,
        subject: String,
        bodyText: String,
        attachments: List<EmailAttachment> = emptyList(),
        context: String = "",
        category: OutboundMessageCategory = OutboundMessageCategory.TRANSACTIONAL
    ): EmailDeliveryResult {
        moduleBlockReason(studioId, category)?.let { return EmailDeliveryResult.failure(it) }

        if (marketingConsentBlocked(customerId, studioId, MarketingChannel.EMAIL, category, context)) {
            return EmailDeliveryResult.failure("Brak zgody na komunikację EMAIL")
        }
        val result = emailProvider.send(to, subject, bodyText, attachments)
        if (result.success) countSent(studioId, MessageChannel.EMAIL, context.ifBlank { category.name })
        return result
    }
}
