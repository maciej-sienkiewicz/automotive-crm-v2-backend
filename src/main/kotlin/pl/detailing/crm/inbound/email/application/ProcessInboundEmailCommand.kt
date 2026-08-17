package pl.detailing.crm.inbound.email.application

import java.time.Instant

/**
 * Cloudflare forwards the parsed message here. The header fields are optional so an older
 * worker deployment keeps working: without them threading falls back to matching sender and
 * subject, with them it becomes deterministic.
 */
data class ProcessInboundEmailCommand(
    val alias: String,
    val from: String,
    val fromName: String? = null,
    val subject: String?,
    val body: String,
    val bodyHtml: String? = null,
    val messageId: String? = null,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val sentAt: Instant? = null,
    val headers: Map<String, String> = emptyMap()
)
