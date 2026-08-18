package pl.detailing.crm.mailbox

import kotlinx.coroutines.runBlocking
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
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.util.UUID

/**
 * Mailbox onboarding: provider detection from an address (MX), connecting with
 * credentials, connection state, disconnecting. The conversation/webmail API lives
 * in [pl.detailing.crm.comms.api.CommsController].
 */
@RestController
@RequestMapping("/api/v1/mailbox")
@RequiresPermission(Permission.LEADS_MANAGE)
class MailboxController(
    private val mailAccountService: MailAccountService
) {

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
}
