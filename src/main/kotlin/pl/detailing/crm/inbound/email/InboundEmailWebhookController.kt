package pl.detailing.crm.inbound.email

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.inbound.email.application.ProcessInboundEmailCommand
import pl.detailing.crm.inbound.email.application.ProcessInboundEmailHandler
import pl.detailing.crm.inbound.email.application.ProcessInboundEmailResult

/**
 * Public endpoint for CloudFlare Email Workers.
 *
 * CloudFlare intercepts emails sent to <alias>@<domain>, parses the message,
 * and POSTs the content here. Access is restricted by a shared secret token
 * validated upstream by CloudflareWebhookTokenFilter (X-Cloudflare-Email-Token header).
 *
 * Always returns HTTP 200 so CloudFlare does not retry on business-logic non-matches
 * (e.g. email not classified as a lead, unknown alias).
 */
@RestController
@RequestMapping("/api/v1/inbound/email")
class InboundEmailWebhookController(
    private val handler: ProcessInboundEmailHandler
) {

    @PostMapping
    fun handleInboundEmail(
        @RequestBody request: CloudflareEmailRequest
    ): ResponseEntity<CloudflareEmailResponse> = runBlocking {
        val result = handler.handle(
            ProcessInboundEmailCommand(
                alias = request.alias.trim(),
                from = request.from.trim(),
                fromName = request.fromName?.trim(),
                subject = request.subject?.trim(),
                body = request.body.trim(),
                bodyHtml = request.bodyHtml,
                messageId = request.messageId?.trim(),
                inReplyTo = request.inReplyTo?.trim(),
                references = request.references.orEmpty(),
                headers = request.headers.orEmpty()
            )
        )

        ResponseEntity.ok(
            CloudflareEmailResponse(
                received = true,
                leadCreated = result is ProcessInboundEmailResult.LeadCreated,
                leadId = (result as? ProcessInboundEmailResult.LeadCreated)?.leadId
            )
        )
    }
}

/**
 * Everything beyond alias/from/subject/body is optional: a worker deployment that does not
 * forward the RFC 5322 headers yet keeps working, it just falls back to subject-based
 * threading instead of the deterministic Message-ID chain.
 */
data class CloudflareEmailRequest(
    val alias: String,
    val from: String,
    val fromName: String? = null,
    val subject: String?,
    val body: String,
    val bodyHtml: String? = null,
    val messageId: String? = null,
    val inReplyTo: String? = null,
    val references: List<String>? = null,
    val headers: Map<String, String>? = null
)

data class CloudflareEmailResponse(
    val received: Boolean,
    val leadCreated: Boolean,
    val leadId: String?
)
