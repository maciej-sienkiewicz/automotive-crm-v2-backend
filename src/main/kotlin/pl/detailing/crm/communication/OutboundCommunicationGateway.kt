package pl.detailing.crm.communication

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import java.util.UUID

/**
 * Single infrastructure-level gateway for all outbound communication.
 *
 * Every SMS and email in the system MUST go through this gateway — never via
 * SmsProvider or EmailProvider directly from application code.
 *
 * Responsibilities enforced here for free, for every caller:
 *   1. Marketing consent check — if the studio has an active consent definition
 *      for the channel and the customer has not signed it, the message is blocked
 *      and a WARN is logged. The caller receives a failed delivery result.
 *   2. Delegating to the actual transport provider.
 *
 * Because the check lives here, new send paths automatically inherit it without
 * any extra effort from the developer.
 */
@Service
class OutboundCommunicationGateway(
    private val smsProvider: SmsProvider,
    private val emailProvider: EmailProvider,
    private val consentChecker: MarketingConsentChecker
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
        return smsProvider.send(phoneNumber, message)
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
