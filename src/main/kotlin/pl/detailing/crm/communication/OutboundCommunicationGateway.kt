package pl.detailing.crm.communication

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.queue.OutboundMessageDraft
import pl.detailing.crm.communication.queue.OutboundMessageQueue
import pl.detailing.crm.communication.queue.QueuedOutboundMessage
import pl.detailing.crm.communication.redirect.ActiveRedirect
import pl.detailing.crm.communication.redirect.CommunicationRedirectService
import pl.detailing.crm.communication.whitelist.RecipientWhitelist
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.domain.MessageChannel
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.smscredits.SmsCreditService
import pl.detailing.crm.smscampaigns.sendername.SmsSenderNameResolver
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Single infrastructure-level gateway for all outbound communication.
 *
 * Every SMS and email addressed to a customer MUST go through this gateway — never via
 * SmsProvider or EmailProvider directly from application code. (Messages to the studio's
 * own staff — password reset, employee invitation, problem reports — are not customer
 * communication and keep using the providers directly.)
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
 *   3. Send window — outside the hours in which the studio talks to customers
 *      ([SendWindow], 12:00–18:00 by default) the message is not sent but queued
 *      ([OutboundMessageQueue]) and goes out at the next opening. The caller gets a
 *      successful result flagged `queued`, with the planned time. Only
 *      [DeliveryPolicy.IMMEDIATE] skips the queue — see that enum for the short list of
 *      legitimate reasons. Checked after 1–2 so a message the studio may not send at
 *      all is rejected now, not tomorrow at noon.
 *   4. SMS credit check — blocked if the studio has no available credits.
 *      Credits are deducted atomically (SELECT FOR UPDATE) before the send attempt.
 *      If the provider call fails, the credit is refunded automatically.
 *   5. Studio redirect — when the studio switched on "send every message to me", the
 *      recipient is replaced by the studio's own phone / e-mail at the very last step,
 *      and the message is stamped with the customer it was meant for (see [redirected]).
 *      This is the ONLY place the swap happens, which is exactly why customer messages
 *      may not bypass the gateway.
 *   6. Recipient whitelist — while [RecipientWhitelist] is in force, a customer message
 *      may only go to a listed number or address. A redirected message is exempt: it goes
 *      to the studio itself, which is the whole point of the redirect. Checked before the
 *      credit deduction, so a blocked SMS costs nothing.
 *   7. Delegating to the actual transport provider.
 *
 * A queued message re-enters at step 1 when the dispatcher hands it back
 * ([deliverQueued]): entitlement, consent, redirect, whitelist and credits are all
 * evaluated at the moment of the real send, not at the moment of queueing. Steps 4–7
 * are therefore never evaluated for a message that is only being queued.
 *
 * Because all checks live here, new send paths automatically inherit them
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
    private val businessEventPublisher: BusinessEventPublisher,
    private val redirectService: CommunicationRedirectService,
    private val whitelist: RecipientWhitelist,
    private val sendWindow: SendWindow,
    private val messageQueue: OutboundMessageQueue,
    /** Podmieniany w testach; produkcyjnie zegar systemowy (Spring bierze wartość domyślną). */
    private val clock: Clock = Clock.systemUTC()
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
     * Odłożenie do kolejki też nie jest wysyłką — policzy się, gdy dispatcher ją wyśle.
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

    /**
     * Odkłada wiadomość, jeśli teraz nie wolno jej wysłać. Null = wolno, wysyłaj.
     *
     * Ta decyzja zapada PO sprawdzeniu modułu i zgody (wiadomość, której studio nie ma prawa
     * wysłać, ma zostać odrzucona od razu, a nie jutro w południe) i PRZED przekierowaniem,
     * whitelistą i kredytami — te trzy rozstrzygają się w chwili faktycznej wysyłki, kiedy
     * dispatcher poda wiadomość z powrotem przez [deliverQueued].
     */
    private fun deferOutsideWindow(delivery: DeliveryPolicy, draft: OutboundMessageDraft, now: Instant): Instant? {
        if (delivery == DeliveryPolicy.IMMEDIATE) return null
        if (sendWindow.contains(now)) return null
        val scheduledFor = sendWindow.nextSlotFrom(now)
        meterRegistry.counter("communication.deferred", "channel", draft.channel.name, "category", draft.category.name).increment()
        return scheduledFor
    }


    /** The address a message actually goes to, what to stamp on it, and whether it was swapped. */
    private data class Recipient(val address: String, val prefix: String, val redirected: Boolean)

    /**
     * Whitelist verdict for a resolved recipient. A redirected message is never blocked —
     * the studio asked to see it on its own phone. Every block is counted
     * (`communication.blocked.whitelist`) so a non-zero rate is visible in Grafana.
     */
    private fun whitelistBlockReason(studioId: UUID, recipient: Recipient, channel: MessageChannel): String? {
        if (recipient.redirected) return null
        val allowed = when (channel) {
            MessageChannel.SMS -> whitelist.allowsPhone(recipient.address)
            MessageChannel.EMAIL -> whitelist.allowsEmail(recipient.address)
            else -> true
        }
        if (allowed) return null
        meterRegistry.counter("communication.blocked.whitelist", "channel", channel.name).increment()
        logger.warn("Outbound {} blocked by recipient whitelist for studio={}: {}", channel, studioId, recipient.address)
        return when (channel) {
            MessageChannel.SMS -> RecipientWhitelist.BLOCK_REASON_SMS
            else -> RecipientWhitelist.BLOCK_REASON_EMAIL
        }
    }

    /**
     * Applies the studio's redirect, if any. Counted and logged per send, because a redirect
     * that is on is the single most common reason a customer "did not get the SMS".
     */
    private fun redirected(
        studioId: UUID,
        original: String,
        channel: MessageChannel,
        pick: (ActiveRedirect) -> String
    ): Recipient {
        val redirect = redirectService.activeFor(studioId) ?: return Recipient(original, "", redirected = false)
        val target = pick(redirect)
        meterRegistry.counter("communication.redirected", "channel", channel.name).increment()
        logger.info("Outbound {} redirected for studio={}: {} -> {}", channel, studioId, original, target)
        return Recipient(target, redirect.prefixFor(original), redirected = true)
    }

    fun sendSms(
        customerId: UUID,
        studioId: UUID,
        phoneNumber: String,
        message: String,
        context: String = "",
        category: OutboundMessageCategory = OutboundMessageCategory.TRANSACTIONAL,
        delivery: DeliveryPolicy = DeliveryPolicy.SEND_WINDOW
    ): SmsDeliveryResult {
        moduleBlockReason(studioId, category)?.let { return SmsDeliveryResult.failure(it) }

        if (marketingConsentBlocked(customerId, studioId, MarketingChannel.SMS, category, context)) {
            return SmsDeliveryResult.failure("Brak zgody na komunikację SMS")
        }

        val resolvedContext = context.ifBlank { category.name }
        val draft = OutboundMessageDraft(
            studioId, customerId, CommunicationChannel.SMS, category, phoneNumber, null, message, resolvedContext
        )
        deferOutsideWindow(delivery, draft, clock.instant())?.let { scheduledFor ->
            val queued = messageQueue.enqueue(draft, scheduledFor)
            return SmsDeliveryResult.queued(queued.id, scheduledFor)
        }
        return dispatchSms(studioId, phoneNumber, message, resolvedContext)
    }

    fun sendTransactionalSms(
        studioId: UUID,
        phoneNumber: String,
        message: String,
        category: OutboundMessageCategory = OutboundMessageCategory.TRANSACTIONAL,
        delivery: DeliveryPolicy = DeliveryPolicy.SEND_WINDOW
    ): SmsDeliveryResult {
        moduleBlockReason(studioId, category)?.let { return SmsDeliveryResult.failure(it) }

        val draft = OutboundMessageDraft(
            studioId, null, CommunicationChannel.SMS, category, phoneNumber, null, message, category.name
        )
        deferOutsideWindow(delivery, draft, clock.instant())?.let { scheduledFor ->
            val queued = messageQueue.enqueue(draft, scheduledFor)
            return SmsDeliveryResult.queued(queued.id, scheduledFor)
        }
        return dispatchSms(studioId, phoneNumber, message, category.name)
    }

    private fun dispatchSms(studioId: UUID, phoneNumber: String, message: String, context: String): SmsDeliveryResult {
        val recipient = redirected(studioId, phoneNumber, MessageChannel.SMS) { it.phone }
        whitelistBlockReason(studioId, recipient, MessageChannel.SMS)?.let { return SmsDeliveryResult.failure(it) }

        val creditDeducted = smsCreditService.tryDeductCredit(StudioId(studioId))
        if (!creditDeducted) {
            logger.warn("SMS blocked — insufficient credits for studio={}", studioId)
            throw InsufficientSmsCreditsException("Brak kredytów SMS. Doładuj konto w panelu zarządzania.")
        }

        val senderName = resolveSmsSenderName(studioId)
        val result = smsProvider.send(recipient.address, recipient.prefix + message, senderName)

        if (!result.success) {
            smsCreditService.refundCredit(StudioId(studioId), "Błąd dostawcy SMS: ${result.errorMessage}")
        } else {
            countSent(studioId, MessageChannel.SMS, context)
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
        category: OutboundMessageCategory = OutboundMessageCategory.TRANSACTIONAL,
        delivery: DeliveryPolicy = DeliveryPolicy.SEND_WINDOW
    ): EmailDeliveryResult {
        moduleBlockReason(studioId, category)?.let { return EmailDeliveryResult.failure(it) }

        if (marketingConsentBlocked(customerId, studioId, MarketingChannel.EMAIL, category, context)) {
            return EmailDeliveryResult.failure("Brak zgody na komunikację EMAIL")
        }

        val resolvedContext = context.ifBlank { category.name }
        val draft = OutboundMessageDraft(
            studioId, customerId, CommunicationChannel.EMAIL, category, to, subject, bodyText, resolvedContext, attachments
        )
        deferOutsideWindow(delivery, draft, clock.instant())?.let { scheduledFor ->
            val queued = messageQueue.enqueue(draft, scheduledFor)
            return EmailDeliveryResult.queued(queued.id, scheduledFor)
        }
        return dispatchEmail(studioId, to, subject, bodyText, attachments, resolvedContext)
    }

    /**
     * E-mail without a customer record behind it (a contractor's monthly statement, a
     * "send a test to myself" from the campaign editor). Same entitlement and redirect
     * rules; no marketing-consent question, because there is no customer to ask about.
     */
    fun sendTransactionalEmail(
        studioId: UUID,
        to: String,
        subject: String,
        bodyText: String,
        attachments: List<EmailAttachment> = emptyList(),
        category: OutboundMessageCategory = OutboundMessageCategory.TRANSACTIONAL,
        delivery: DeliveryPolicy = DeliveryPolicy.SEND_WINDOW
    ): EmailDeliveryResult {
        moduleBlockReason(studioId, category)?.let { return EmailDeliveryResult.failure(it) }

        val draft = OutboundMessageDraft(
            studioId, null, CommunicationChannel.EMAIL, category, to, subject, bodyText, category.name, attachments
        )
        deferOutsideWindow(delivery, draft, clock.instant())?.let { scheduledFor ->
            val queued = messageQueue.enqueue(draft, scheduledFor)
            return EmailDeliveryResult.queued(queued.id, scheduledFor)
        }
        return dispatchEmail(studioId, to, subject, bodyText, attachments, category.name)
    }

    private fun dispatchEmail(
        studioId: UUID,
        to: String,
        subject: String,
        bodyText: String,
        attachments: List<EmailAttachment>,
        context: String
    ): EmailDeliveryResult {
        // The stamp goes on the subject, not the body: the body must be exactly what a
        // customer would read, so the person reviewing it judges the real thing.
        val recipient = redirected(studioId, to, MessageChannel.EMAIL) { it.email }
        whitelistBlockReason(studioId, recipient, MessageChannel.EMAIL)?.let { return EmailDeliveryResult.failure(it) }
        val result = emailProvider.send(recipient.address, recipient.prefix + subject, bodyText, attachments)
        if (result.success) countSent(studioId, MessageChannel.EMAIL, context)
        return result
    }

    // ── Queue re-entry ──────────────────────────────────────────────────────

    /** Outcome of sending a queued message; [retryable] says whether another attempt makes sense. */
    data class QueuedDeliveryOutcome(
        val success: Boolean,
        val externalMessageId: String?,
        val errorMessage: String?,
        val retryable: Boolean
    )

    /**
     * Wysyłka wiadomości z kolejki — pełny przebieg bramki, jakby wywołujący wołał ją
     * teraz, tylko z [DeliveryPolicy.IMMEDIATE] (dispatcher sam pilnuje, że jest w oknie;
     * bez tego wiadomość z 18:00:30 wróciłaby do kolejki na jutro).
     *
     * Brak kredytów nie jest ponawiany — tak samo traktują go pozostałe dispatchery — i nie
     * rzuca wyjątku: dziennik ma dostać czytelny powód, a nie stack trace.
     */
    fun deliverQueued(message: QueuedOutboundMessage): QueuedDeliveryOutcome {
        val entity = message.entity
        return try {
            when (entity.channel) {
                CommunicationChannel.SMS -> {
                    val result = if (entity.customerId != null) {
                        sendSms(
                            customerId = entity.customerId,
                            studioId = entity.studioId,
                            phoneNumber = entity.recipient,
                            message = entity.body,
                            context = entity.context,
                            category = entity.category,
                            delivery = DeliveryPolicy.IMMEDIATE
                        )
                    } else {
                        sendTransactionalSms(
                            studioId = entity.studioId,
                            phoneNumber = entity.recipient,
                            message = entity.body,
                            category = entity.category,
                            delivery = DeliveryPolicy.IMMEDIATE
                        )
                    }
                    QueuedDeliveryOutcome(result.success, result.externalMessageId, result.errorMessage, retryable = true)
                }
                CommunicationChannel.EMAIL -> {
                    val result = if (entity.customerId != null) {
                        sendEmail(
                            customerId = entity.customerId,
                            studioId = entity.studioId,
                            to = entity.recipient,
                            subject = entity.subject.orEmpty(),
                            bodyText = entity.body,
                            attachments = message.attachments,
                            context = entity.context,
                            category = entity.category,
                            delivery = DeliveryPolicy.IMMEDIATE
                        )
                    } else {
                        sendTransactionalEmail(
                            studioId = entity.studioId,
                            to = entity.recipient,
                            subject = entity.subject.orEmpty(),
                            bodyText = entity.body,
                            attachments = message.attachments,
                            category = entity.category,
                            delivery = DeliveryPolicy.IMMEDIATE
                        )
                    }
                    QueuedDeliveryOutcome(result.success, result.messageId, result.errorMessage, retryable = true)
                }
            }
        } catch (e: InsufficientSmsCreditsException) {
            logger.warn("Queued SMS skipped — no credits | id={} studio={}", entity.id, entity.studioId)
            QueuedDeliveryOutcome(false, null, e.message ?: "Brak kredytów SMS", retryable = false)
        }
    }
}
