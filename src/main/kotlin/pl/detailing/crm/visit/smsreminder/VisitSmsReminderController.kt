package pl.detailing.crm.visit.smsreminder

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.VisitId
import java.util.UUID

data class SetSmsReminderRequest(val suppressed: Boolean)

/**
 * PATCH /api/visits/{visitId}/sms-reminder
 *
 * Enables or disables the delayed post-service SMS reminder for a single visit.
 * The reminder is enabled by default and can be suppressed on a per-visit basis.
 *
 * Request:  { "suppressed": true }
 * Response: { "visitId": "...", "smsReminderSuppressed": true }
 */
@RestController
@RequestMapping("/api/visits")
class VisitSmsReminderController(
    private val handler: SetVisitSmsReminderHandler
) {
    @PatchMapping("/{visitId}/sms-reminder")
    fun setSmsReminder(
        @PathVariable visitId: UUID,
        @RequestBody request: SetSmsReminderRequest
    ): ResponseEntity<SetVisitSmsReminderResult> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = handler.handle(
            SetVisitSmsReminderCommand(
                studioId = principal.studioId,
                visitId = VisitId(visitId),
                suppressed = request.suppressed
            )
        )

        return ResponseEntity.ok(result)
    }
}
