package pl.detailing.crm.comms.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.comms.engine.CommsReadService
import pl.detailing.crm.comms.engine.ImapSyncEngine
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.insights.ContactInsightsDto
import pl.detailing.crm.comms.insights.GetContactInsightsHandler
import pl.detailing.crm.comms.send.SendMailCommand
import pl.detailing.crm.comms.send.SendMailHandler
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.NotFoundException
import java.util.UUID

data class SendMailRequest(
    val accountId: String?,
    val threadId: String?,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String?,
    val bodyHtml: String
)

data class SendMailResponse(val messageId: String, val threadId: String)

data class CreateLabelRequest(val name: String, val color: String?)

data class SetThreadLabelRequest(val labelId: String?)

data class SetThreadArchivedRequest(val archived: Boolean)

/**
 * Webmail API: threads, messages, sending, attachments, local folders, insights.
 * Mailbox onboarding (detect/connect/disconnect) stays in MailboxController — that
 * flow predates this module and is shared with it.
 */
@RestController
@RequestMapping("/api/v1/comms")
@RequiresPermission(Permission.LEADS_MANAGE)
class CommsController(
    private val queryHandlers: CommsQueryHandlers,
    private val readService: CommsReadService,
    private val sendMailHandler: SendMailHandler,
    private val insightsHandler: GetContactInsightsHandler,
    private val attachmentRepository: CommAttachmentRepository,
    private val accountRepository: MailAccountRepository,
    private val syncEngine: ImapSyncEngine
) {

    @GetMapping("/accounts")
    fun accounts(): ResponseEntity<List<MailAccountStateDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            accountRepository.findByStudioId(principal.studioId.value).map { it.toStateDto() }
        )
    }

    /** Manual "sync now" — also fired by the UI when the user opens the mail module. */
    @PostMapping("/accounts/{id}/sync")
    fun syncNow(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val account = accountRepository.findByIdAndStudioId(UUID.fromString(id), principal.studioId.value)
            ?: throw NotFoundException("Nie znaleziono skrzynki")
        Thread { syncEngine.syncAccount(account.id) }.start()
        return ResponseEntity.accepted().build()
    }

    @GetMapping("/threads")
    fun listThreads(
        @RequestParam(required = false) accountId: String?,
        @RequestParam(defaultValue = "false") archived: Boolean,
        @RequestParam(required = false) labelId: String?,
        @RequestParam(defaultValue = "false") onlyUnread: Boolean,
        @RequestParam(defaultValue = "false") onlyLeads: Boolean,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "30") pageSize: Int
    ): ResponseEntity<CommThreadPageDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            queryHandlers.listThreads(
                principal.studioId,
                ThreadListFilter(
                    accountId = accountId?.let(UUID::fromString),
                    archived = archived,
                    labelId = labelId?.let(UUID::fromString),
                    onlyUnread = onlyUnread,
                    onlyLeads = onlyLeads,
                    query = query,
                    page = page,
                    pageSize = pageSize
                )
            )
        )
    }

    @GetMapping("/threads/{id}")
    fun getThread(@PathVariable id: String): ResponseEntity<CommThreadDetailDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(queryHandlers.getThread(principal.studioId, UUID.fromString(id)))
    }

    /** Opening a conversation marks it read — locally at once, on the server via the outbox. */
    @PostMapping("/threads/{id}/read")
    fun markThreadRead(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        readService.markThreadReadFromCrm(principal.studioId.value, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/messages/{id}/read")
    fun markMessageRead(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        readService.markReadFromCrm(principal.studioId.value, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/send")
    fun send(@RequestBody request: SendMailRequest): ResponseEntity<SendMailResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = sendMailHandler.handle(
            SendMailCommand(
                studioId = principal.studioId,
                accountId = request.accountId?.let(UUID::fromString),
                threadId = request.threadId?.let(UUID::fromString),
                to = request.to,
                cc = request.cc,
                subject = request.subject,
                bodyHtml = request.bodyHtml
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SendMailResponse(result.messageId.toString(), result.threadId.toString()))
    }

    @GetMapping("/attachments/{id}")
    fun downloadAttachment(@PathVariable id: String): ResponseEntity<ByteArray> =
        serveAttachment(id, asDownload = true)

    /** Inline images (cid:) referenced from sanitised message HTML. */
    @GetMapping("/attachments/{id}/inline")
    fun inlineAttachment(@PathVariable id: String): ResponseEntity<ByteArray> =
        serveAttachment(id, asDownload = false)

    private fun serveAttachment(id: String, asDownload: Boolean): ResponseEntity<ByteArray> {
        val principal = SecurityContextHelper.getCurrentUser()
        val attachment = attachmentRepository.findByIdAndStudioId(UUID.fromString(id), principal.studioId.value)
            ?: throw NotFoundException("Nie znaleziono załącznika")

        val safeName = attachment.fileName.replace(Regex("[\\r\\n\"\\\\]"), "_")
        val disposition = if (asDownload) "attachment" else "inline"
        // nosniff + a forced download for anything non-image keeps an HTML "invoice"
        // from ever rendering in the CRM's origin.
        val effectiveDisposition =
            if (!asDownload && !attachment.contentType.startsWith("image/")) "attachment" else disposition

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "$effectiveDisposition; filename=\"$safeName\"")
            .header("X-Content-Type-Options", "nosniff")
            .contentType(
                runCatching { MediaType.parseMediaType(attachment.contentType) }
                    .getOrDefault(MediaType.APPLICATION_OCTET_STREAM)
            )
            .body(attachment.content)
    }

    @GetMapping("/labels")
    fun labels(): ResponseEntity<List<CommLabelDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(queryHandlers.listLabels(principal.studioId))
    }

    @PostMapping("/labels")
    fun createLabel(@RequestBody request: CreateLabelRequest): ResponseEntity<CommLabelDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(queryHandlers.createLabel(principal.studioId, request.name, request.color))
    }

    @DeleteMapping("/labels/{id}")
    fun deleteLabel(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        queryHandlers.deleteLabel(principal.studioId, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/threads/{id}/label")
    fun setThreadLabel(
        @PathVariable id: String,
        @RequestBody request: SetThreadLabelRequest
    ): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        queryHandlers.setThreadLabel(
            principal.studioId, UUID.fromString(id), request.labelId?.let(UUID::fromString)
        )
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/threads/{id}/archive")
    fun setThreadArchived(
        @PathVariable id: String,
        @RequestBody request: SetThreadArchivedRequest
    ): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        queryHandlers.setThreadArchived(principal.studioId, UUID.fromString(id), request.archived)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/insights")
    fun insights(
        @RequestParam email: String,
        @RequestParam(required = false) threadId: String?
    ): ResponseEntity<ContactInsightsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(insightsHandler.handle(principal.studioId, email, threadId))
    }
}
