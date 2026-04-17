package pl.detailing.crm.smscampaigns.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import java.time.Instant
import java.util.UUID

/**
 * JPA entity backing [SmsAutomationConfig].
 * One row per studio; the studioId IS the business key (unique constraint).
 */
@Entity
@Table(
    name = "sms_automation_configs",
    indexes = [Index(name = "idx_sms_automation_configs_studio_id", columnList = "studio_id", unique = true)]
)
class SmsAutomationConfigEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid", unique = true)
    val studioId: UUID,

    // ── PRE-VISIT RULE ──────────────────────────────────────────────────────────

    @Column(name = "pre_visit_enabled", nullable = false)
    var preVisitEnabled: Boolean,

    @Column(name = "pre_visit_offset_minutes", nullable = false)
    var preVisitOffsetMinutes: Int,

    @Column(name = "pre_visit_message_template", nullable = false, columnDefinition = "TEXT")
    var preVisitMessageTemplate: String,

    // ── POST-VISIT RULE ─────────────────────────────────────────────────────────

    @Column(name = "post_visit_enabled", nullable = false)
    var postVisitEnabled: Boolean,

    @Column(name = "post_visit_offset_minutes", nullable = false)
    var postVisitOffsetMinutes: Int,

    @Column(name = "post_visit_message_template", nullable = false, columnDefinition = "TEXT")
    var postVisitMessageTemplate: String,

    // ── DELAYED REMINDER RULE ───────────────────────────────────────────────────
    // Fires [offsetMinutes] after visit.pickupDate (when customer collected the car).
    // Enabled by default; can be suppressed per-visit via Visit.smsReminderSuppressed.

    @Column(name = "delayed_reminder_enabled", nullable = false)
    var delayedReminderEnabled: Boolean,

    @Column(name = "delayed_reminder_offset_minutes", nullable = false)
    var delayedReminderOffsetMinutes: Int,

    @Column(name = "delayed_reminder_message_template", nullable = false, columnDefinition = "TEXT")
    var delayedReminderMessageTemplate: String,

    // ── AUDIT ───────────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): SmsAutomationConfig = SmsAutomationConfig(
        studioId = StudioId(studioId),
        preVisit = SmsAutomationRule(
            enabled = preVisitEnabled,
            offsetMinutes = preVisitOffsetMinutes,
            messageTemplate = preVisitMessageTemplate
        ),
        postVisit = SmsAutomationRule(
            enabled = postVisitEnabled,
            offsetMinutes = postVisitOffsetMinutes,
            messageTemplate = postVisitMessageTemplate
        ),
        delayedReminder = SmsAutomationRule(
            enabled = delayedReminderEnabled,
            offsetMinutes = delayedReminderOffsetMinutes,
            messageTemplate = delayedReminderMessageTemplate
        )
    )

    companion object {
        fun fromDomain(config: SmsAutomationConfig, existingId: UUID? = null): SmsAutomationConfigEntity =
            SmsAutomationConfigEntity(
                id = existingId ?: UUID.randomUUID(),
                studioId = config.studioId.value,
                preVisitEnabled = config.preVisit.enabled,
                preVisitOffsetMinutes = config.preVisit.offsetMinutes,
                preVisitMessageTemplate = config.preVisit.messageTemplate,
                postVisitEnabled = config.postVisit.enabled,
                postVisitOffsetMinutes = config.postVisit.offsetMinutes,
                postVisitMessageTemplate = config.postVisit.messageTemplate,
                delayedReminderEnabled = config.delayedReminder.enabled,
                delayedReminderOffsetMinutes = config.delayedReminder.offsetMinutes,
                delayedReminderMessageTemplate = config.delayedReminder.messageTemplate,
                updatedAt = Instant.now()
            )
    }
}
