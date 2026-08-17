package pl.detailing.crm.mailbox

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.mailbox.account.ConnectMailAccountCommand
import pl.detailing.crm.mailbox.account.DetectMailProviderQuery
import pl.detailing.crm.mailbox.account.MailAccountService
import pl.detailing.crm.mailbox.reply.SendThreadReplyCommand
import pl.detailing.crm.mailbox.reply.SendThreadReplyHandler
import pl.detailing.crm.mailbox.thread.ApplyReviewDecisionHandler
import pl.detailing.crm.mailbox.thread.GetLeadThreadHandler
import pl.detailing.crm.mailbox.thread.GetLeadThreadQuery
import pl.detailing.crm.mailbox.thread.GetReviewQueueHandler
import pl.detailing.crm.mailbox.thread.ReviewDecisionCommand
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission
import java.util.UUID

/**
 * Mailbox onboarding, conversation view and replying from inside the CRM.
 *
 * Everything here is scoped by the caller's studio; ids from the path are always
 * re-checked against it in the handlers.
 */
@RestController
@RequestMapping("/api/v1/mailbox")
@RequiresPermission(Permission.LEADS_MANAGE)
class MailboxController(
    private val mailAccountService: MailAccountService,
    private val getLeadThreadHandler: GetLeadThreadHandler,
    private val sendThreadReplyHandler: SendThreadReplyHandler,
    private val getReviewQueueHandler: GetReviewQueueHandler,
    private val applyReviewDecisionHandler: ApplyReviewDecisionHandler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Detects provider settings from an address alone.
     * POST /api/v1/mailbox/accounts/detect
     */
    @PostMapping("/accounts/detect")
    fun detectProvider(@RequestBody request: DetectProviderRequest): ResponseEntity<DetectProviderResponse> {
        val detection = mailAccountService.detect(DetectMailProviderQuery(request.email))
        return ResponseEntity.ok(detection.toResponse())
    }

    /**
     * Connects a mailbox over IMAP/SMTP after verifying the login.
     * POST /api/v1/mailbox/accounts
     */
    @PostMapping("/accounts")
    fun connectAccount(@RequestBody request: ConnectMailAccountRequest): ResponseEntity<MailAccountResponse> =
        runBlocking {
            val principal = SecurityContextHelper.getCurrentUser()
            val account = mailAccountService.connect(
                ConnectMailAccountCommand(
                    studioId = principal.studioId,
                    email = request.email,
                    password = request.password,
                    imapHost = request.imapHost,
                    imapPort = request.imapPort,
                    smtpHost = request.smtpHost,
                    smtpPort = request.smtpPort
                )
            )
            ResponseEntity.status(HttpStatus.CREATED).body(account.toResponse())
        }

    /**
     * Lists connected mailboxes with their connection state.
     * GET /api/v1/mailbox/accounts
     */
    @GetMapping("/accounts")
    fun listAccounts(): ResponseEntity<List<MailAccountResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(mailAccountService.list(principal.studioId).map { it.toResponse() })
    }

    /**
     * Disconnects a mailbox and drops its stored credentials.
     * DELETE /api/v1/mailbox/accounts/{id}
     */
    @DeleteMapping("/accounts/{id}")
    fun disconnectAccount(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        mailAccountService.disconnect(principal.studioId, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }

    /**
     * Triggers an out-of-schedule sync.
     * POST /api/v1/mailbox/accounts/{id}/sync
     */
    @PostMapping("/accounts/{id}/sync")
    fun syncAccount(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        mailAccountService.syncNow(principal.studioId, UUID.fromString(id))
        ResponseEntity.accepted().build()
    }

    /**
     * Full conversation behind a lead, denoised.
     * GET /api/v1/mailbox/leads/{leadId}/thread
     */
    @GetMapping("/leads/{leadId}/thread")
    fun getLeadThread(@PathVariable leadId: String): ResponseEntity<LeadThreadResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val view = getLeadThreadHandler.handle(
            GetLeadThreadQuery(leadId = UUID.fromString(leadId), studioId = principal.studioId)
        )
        ResponseEntity.ok(view.toResponse())
    }

    /**
     * Replies to a conversation from the studio's own mailbox.
     * POST /api/v1/mailbox/threads/{threadId}/reply
     */
    @PostMapping("/threads/{threadId}/reply")
    fun reply(
        @PathVariable threadId: String,
        @RequestBody request: SendReplyRequest
    ): ResponseEntity<SendReplyResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = sendThreadReplyHandler.handle(
            SendThreadReplyCommand(
                threadId = UUID.fromString(threadId),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                bodyHtml = request.bodyHtml
            )
        )
        ResponseEntity.ok(SendReplyResponse(messageId = result.messageId, leadId = result.leadId))
    }

    /**
     * Conversations the classifier was unsure about.
     * GET /api/v1/mailbox/review
     */
    @GetMapping("/review")
    fun reviewQueue(): ResponseEntity<List<ReviewQueueItemResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(getReviewQueueHandler.handle(principal.studioId).map { it.toResponse() })
    }

    /**
     * Human verdict for an unsure conversation; remembers the sender for next time.
     * POST /api/v1/mailbox/review/{threadId}/decision
     */
    @PostMapping("/review/{threadId}/decision")
    fun reviewDecision(
        @PathVariable threadId: String,
        @RequestBody request: ReviewDecisionRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        applyReviewDecisionHandler.handle(
            ReviewDecisionCommand(
                threadId = UUID.fromString(threadId),
                studioId = principal.studioId,
                decision = request.decision
            )
        )
        ResponseEntity.noContent().build()
    }
}
