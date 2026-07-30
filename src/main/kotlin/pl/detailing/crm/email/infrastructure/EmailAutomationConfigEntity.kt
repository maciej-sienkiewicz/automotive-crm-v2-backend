package pl.detailing.crm.email.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.email.domain.EmailNotificationRule
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "email_automation_configs",
    indexes = [Index(name = "idx_email_automation_configs_studio_id", columnList = "studio_id", unique = true)]
)
class EmailAutomationConfigEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid", unique = true)
    val studioId: UUID,

    // ── VISIT WELCOME ────────────────────────────────────────────────────────────

    @Column(name = "visit_welcome_enabled", nullable = false)
    var visitWelcomeEnabled: Boolean,

    @Column(name = "visit_welcome_subject_template", nullable = false, columnDefinition = "TEXT")
    var visitWelcomeSubjectTemplate: String,

    @Column(name = "visit_welcome_body_template", nullable = false, columnDefinition = "TEXT")
    var visitWelcomeBodyTemplate: String,

    // ── VISIT READY FOR PICKUP ───────────────────────────────────────────────────

    @Column(name = "visit_ready_for_pickup_enabled", nullable = false)
    var visitReadyForPickupEnabled: Boolean,

    @Column(name = "visit_ready_for_pickup_subject_template", nullable = false, columnDefinition = "TEXT")
    var visitReadyForPickupSubjectTemplate: String,

    @Column(name = "visit_ready_for_pickup_body_template", nullable = false, columnDefinition = "TEXT")
    var visitReadyForPickupBodyTemplate: String,

    // ── BATCH ORDER CLOSE ────────────────────────────────────────────────────────

    @Column(name = "batch_order_close_enabled", nullable = false)
    var batchOrderCloseEnabled: Boolean = false,

    @Column(name = "batch_order_close_subject_template", columnDefinition = "TEXT")
    var batchOrderCloseSubjectTemplate: String? = null,

    @Column(name = "batch_order_close_body_template", columnDefinition = "TEXT")
    var batchOrderCloseBodyTemplate: String? = null,

    // ── AUDIT ────────────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): EmailAutomationConfig {
        val defaults = EmailAutomationConfig.defaultFor(StudioId(studioId))
        return EmailAutomationConfig(
            studioId = StudioId(studioId),
            visitWelcome = EmailNotificationRule(
                enabled = visitWelcomeEnabled,
                subjectTemplate = visitWelcomeSubjectTemplate,
                bodyTemplate = visitWelcomeBodyTemplate
            ),
            visitReadyForPickup = EmailNotificationRule(
                enabled = visitReadyForPickupEnabled,
                subjectTemplate = visitReadyForPickupSubjectTemplate,
                bodyTemplate = visitReadyForPickupBodyTemplate
            ),
            batchOrderClose = EmailNotificationRule(
                enabled = batchOrderCloseEnabled,
                subjectTemplate = batchOrderCloseSubjectTemplate ?: defaults.batchOrderClose.subjectTemplate,
                bodyTemplate = batchOrderCloseBodyTemplate ?: defaults.batchOrderClose.bodyTemplate
            )
        )
    }

    companion object {
        fun fromDomain(config: EmailAutomationConfig, existingId: UUID? = null): EmailAutomationConfigEntity =
            EmailAutomationConfigEntity(
                id = existingId ?: UUID.randomUUID(),
                studioId = config.studioId.value,
                visitWelcomeEnabled = config.visitWelcome.enabled,
                visitWelcomeSubjectTemplate = config.visitWelcome.subjectTemplate,
                visitWelcomeBodyTemplate = config.visitWelcome.bodyTemplate,
                visitReadyForPickupEnabled = config.visitReadyForPickup.enabled,
                visitReadyForPickupSubjectTemplate = config.visitReadyForPickup.subjectTemplate,
                visitReadyForPickupBodyTemplate = config.visitReadyForPickup.bodyTemplate,
                batchOrderCloseEnabled = config.batchOrderClose.enabled,
                batchOrderCloseSubjectTemplate = config.batchOrderClose.subjectTemplate,
                batchOrderCloseBodyTemplate = config.batchOrderClose.bodyTemplate,
                updatedAt = Instant.now()
            )
    }
}
