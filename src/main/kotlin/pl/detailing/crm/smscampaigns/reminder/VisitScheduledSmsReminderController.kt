package pl.detailing.crm.smscampaigns.reminder

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.util.UUID

// ── Request DTOs ─────────────────────────────────────────────────────────────

data class ScheduleSmsReminderRequest(
    val messageContent: String,
    /** ISO-8601 timestamp. When null defaults to 90 days from now. */
    val scheduledFor: Instant?
)

data class UpdateSmsReminderRequest(
    val messageContent: String,
    val scheduledFor: Instant
)

data class GenerateSmsRequest(
    val scheduledFor: Instant
)

// ── Response DTOs ─────────────────────────────────────────────────────────────

data class GeneratedSmsContentResponse(
    val content: String,
    val charCount: Int
)

data class SmsReminderResponse(
    val id: String,
    val visitId: String,
    val phoneNumber: String,
    val messageContent: String,
    val scheduledFor: Instant,
    val status: String,
    val sentAt: Instant?,
    val externalMessageId: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * Manages per-visit scheduled SMS reminders.
 *
 * POST   /api/visits/{visitId}/sms-reminder//  → draft SMS content via LLM
 * POST   /api/visits/{visitId}/sms-reminder           → schedule reminder
 * GET    /api/visits/{visitId}/sms-reminder           → list all reminders for visit
 * PUT    /api/visits/{visitId}/sms-reminder/{id}      → update content / scheduled time
 * DELETE /api/visits/{visitId}/sms-reminder/{id}      → cancel pending reminder
 */
@RestController
@RequestMapping("/api/visits/{visitId}/sms-reminder")
class VisitScheduledSmsReminderController(
    private val generateHandler: GenerateSmsContentHandler,
    private val scheduleHandler: ScheduleVisitSmsReminderHandler,
    private val getHandler: GetVisitSmsReminderHandler,
    private val updateHandler: UpdateSmsReminderHandler,
    private val cancelHandler: CancelSmsReminderHandler,
    private val studioRepository: StudioRepository
) {
    /**
     * Calls the LLM to draft an SMS based on visit / customer / vehicle data.
     * Nothing is persisted — the user reviews and optionally edits the result
     * before confirming via POST /sms-reminder.
     */
    @PostMapping("/generate")
    fun generateContent(
        @PathVariable visitId: UUID,
        @RequestBody request: GenerateSmsRequest
    ): ResponseEntity<GeneratedSmsContentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val studioName = studioRepository.findByStudioId(principal.studioId.value)?.name
            ?: principal.studioId.value.toString()

        val result = generateHandler.handle(
            GenerateSmsContentCommand(
                studioId = principal.studioId,
                visitId = VisitId(visitId),
                studioName = studioName,
                scheduledFor = request.scheduledFor,
                phoneNumber = principal.phoneNumber
            )
        )

        ResponseEntity.ok(GeneratedSmsContentResponse(content = result.content, charCount = result.charCount))
    }

    /** Schedules a new SMS reminder for the visit. */
    @PostMapping
    fun schedule(
        @PathVariable visitId: UUID,
        @RequestBody request: ScheduleSmsReminderRequest
    ): ResponseEntity<SmsReminderResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val reminder = scheduleHandler.handle(
            ScheduleVisitSmsReminderCommand(
                studioId = principal.studioId,
                visitId = VisitId(visitId),
                userId = principal.userId,
                messageContent = request.messageContent,
                scheduledFor = request.scheduledFor
            )
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(reminder.toResponse())
    }

    /**
     * Returns all reminders for this visit (PENDING, SENT, FAILED, CANCELLED).
     * The frontend uses this to show the current active reminder and history.
     */
    @GetMapping
    fun list(
        @PathVariable visitId: UUID
    ): ResponseEntity<List<SmsReminderResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()

        val reminders = getHandler.handle(
            GetVisitSmsReminderCommand(
                studioId = principal.studioId,
                visitId = VisitId(visitId)
            )
        )

        return ResponseEntity.ok(reminders.map { it.toResponse() })
    }

    /** Updates the message content and/or scheduled time of a PENDING reminder. */
    @PutMapping("/{reminderId}")
    fun update(
        @PathVariable visitId: UUID,
        @PathVariable reminderId: UUID,
        @RequestBody request: UpdateSmsReminderRequest
    ): ResponseEntity<SmsReminderResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val updated = updateHandler.handle(
            UpdateSmsReminderCommand(
                studioId = principal.studioId,
                reminderId = reminderId,
                messageContent = request.messageContent,
                scheduledFor = request.scheduledFor
            )
        )

        return ResponseEntity.ok(updated.toResponse())
    }

    /** Cancels a PENDING reminder before it has been dispatched. */
    @DeleteMapping("/{reminderId}")
    fun cancel(
        @PathVariable visitId: UUID,
        @PathVariable reminderId: UUID
    ): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()

        cancelHandler.handle(
            CancelSmsReminderCommand(
                studioId = principal.studioId,
                reminderId = reminderId
            )
        )

        return ResponseEntity.noContent().build()
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun ScheduledSmsReminder.toResponse() = SmsReminderResponse(
        id = id.toString(),
        visitId = visitId.toString(),
        phoneNumber = phoneNumber,
        messageContent = messageContent,
        scheduledFor = scheduledFor,
        status = status.name,
        sentAt = sentAt,
        externalMessageId = externalMessageId,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
