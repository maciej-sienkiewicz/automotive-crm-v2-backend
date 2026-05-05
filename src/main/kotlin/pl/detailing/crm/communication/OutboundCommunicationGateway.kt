package pl.detailing.crm.communication

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import pl.detailing.crm.smscredits.SmsCreditService
import java.util.UUID

/**
 * Single infrastructure-level gateway for all outbound communication.
 *
 * Every SMS and email in the system MUST go through this gateway — never via
 * SmsProvider or EmailProvider directly from application code.
 *
 * Responsibilities enforced here for free, for every caller:
 *   1. Marketing consent check — blocked if the customer has not signed the required consent.
 *   2. SMS credit check — blocked if the studio has no available credits.
 *      Credits are deducted atomically (SELECT FOR UPDATE) before the send attempt.
 *      If the provider call fails, the credit is refunded automatically.
 *   3. Delegating to the actual transport provider.
 *
 * Because all three checks live here, new send paths automatically inherit them
 * without any extra effort from the developer — and there is no way to bypass them.
 */
@Service
class OutboundCommunicationGateway(
    private val smsProvider: SmsProvider,
    private val emailProvider: EmailProvider,
    private val consentChecker: MarketingConsentChecker,
    private val smsCreditService: SmsCreditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendSms(
        customerId: UUID,
        studioId: UUID,
        phoneNumber: String,
        message: String,
        context: String = ""
    ): SmsDeliveryResult {
        if (!consentChecker.canSend(customerId, studioId, MarketingChannel.SMS, context.ifBlank { "OutboundGateway" })) {
            return SmsDeliveryResult.failure("Brak zgody na komunikację SMS")
        }

        val creditDeducted = smsCreditService.tryDeductCredit(StudioId(studioId))
        if (!creditDeducted) {
            logger.warn("SMS blocked — insufficient credits for studio={}", studioId)
            return SmsDeliveryResult.failure("Brak kredytów SMS. Doładuj konto w panelu zarządzania.")
        }

        val result = smsProvider.send(phoneNumber, message)

        if (!result.success) {
            smsCreditService.refundCredit(StudioId(studioId), "Błąd dostawcy SMS: ${result.errorMessage}")
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
        context: String = ""
    ): EmailDeliveryResult {
        if (!consentChecker.canSend(customerId, studioId, MarketingChannel.EMAIL, context.ifBlank { "OutboundGateway" })) {
            return EmailDeliveryResult.failure("Brak zgody na komunikację EMAIL")
        }
        return emailProvider.send(to, subject, bodyText, attachments)
    }
}
