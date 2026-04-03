package pl.detailing.crm.email.provider

/**
 * Provider-agnostic interface for transactional email dispatch.
 *
 * To swap to a different SMTP provider or service (e.g. SendGrid, Mailgun),
 * register a different implementation of this interface — no other code changes needed.
 */
interface EmailProvider {
    fun send(
        to: String,
        subject: String,
        bodyText: String,
        attachments: List<EmailAttachment> = emptyList()
    ): EmailDeliveryResult
}
