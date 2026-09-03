package pl.detailing.crm.communication.rehearsal

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.communication.template.MessageTemplateKind
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import pl.detailing.crm.shared.pii.Pii
import java.time.Instant

data class RehearsalFindingDto(val severity: Severity, val rule: String, val detail: String)

data class RehearsalItemDto(
    val seq: Int,
    val total: Int,
    val kind: MessageTemplateKind,
    val channel: RehearsalChannel,
    val enabled: Boolean,
    val subject: String?,
    val body: String,
    val segments: Int?,
    val findings: List<RehearsalFindingDto>,
    val delivery: RehearsalDelivery?
)

data class RehearsalReportDto(
    val generatedAt: Instant,
    @Pii val redirectPhone: String?,
    @Pii val redirectEmail: String?,
    val sent: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val items: List<RehearsalItemDto>
)

/**
 * "Wyślij wszystkie szablony testowo na moje dane" — the button next to the redirect switch.
 * `plan` renders and validates without sending; `run` sends only when the plan is clean and
 * the studio's redirect is on.
 */
@RequiresPermission(Permission.COMMUNICATION_SEND)
@RequiresCapability(CapabilityKey.COMM_SEND_TRANSACTIONAL)
@RestController
@RequestMapping("/api/v1/communication/rehearsal")
class CommsRehearsalController(private val runner: CommsRehearsalRunner) {

    @PostMapping("/plan")
    fun plan(): ResponseEntity<RehearsalReportDto> =
        ResponseEntity.ok(runner.plan(SecurityContextHelper.getCurrentStudioId()).toDto())

    @PostMapping("/run")
    fun run(): ResponseEntity<RehearsalReportDto> =
        ResponseEntity.ok(runner.run(SecurityContextHelper.getCurrentStudioId()).toDto())

    private fun RehearsalReport.toDto() = RehearsalReportDto(
        generatedAt = generatedAt,
        redirectPhone = redirectPhone,
        redirectEmail = redirectEmail,
        sent = sent,
        errorCount = errorCount,
        warningCount = warningCount,
        items = items.map {
            RehearsalItemDto(
                it.seq, it.total, it.kind, it.channel, it.enabled, it.subject, it.body, it.segments,
                it.findings.map { f -> RehearsalFindingDto(f.severity, f.rule, f.detail) }, it.delivery
            )
        }
    )
}
