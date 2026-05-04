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

    // ── VISIT CONFIRMED ──────────────────────────────────────────────────────────

    @Column(name = "visit_confirmed_enabled", nullable = false)
    var visitConfirmedEnabled: Boolean,

    @Column(name = "visit_confirmed_subject_template", nullable = false, columnDefinition = "TEXT")
    var visitConfirmedSubjectTemplate: String,

    @Column(name = "visit_confirmed_body_template", nullable = false, columnDefinition = "TEXT")
    var visitConfirmedBodyTemplate: String,

    // ── VISIT READY FOR PICKUP ───────────────────────────────────────────────────

    @Column(name = "visit_ready_for_pickup_enabled", nullable = false)
    var visitReadyForPickupEnabled: Boolean,

    @Column(name = "visit_ready_for_pickup_subject_template", nullable = false, columnDefinition = "TEXT")
    var visitReadyForPickupSubjectTemplate: String,

    @Column(name = "visit_ready_for_pickup_body_template", nullable = false, columnDefinition = "TEXT")
    var visitReadyForPickupBodyTemplate: String,

    // ── AUDIT ────────────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): EmailAutomationConfig = EmailAutomationConfig(
        studioId = StudioId(studioId),
        visitWelcome = EmailNotificationRule(
            enabled = visitWelcomeEnabled,
            subjectTemplate = visitWelcomeSubjectTemplate,
            bodyTemplate = visitWelcomeBodyTemplate
        ),
        visitConfirmed = EmailNotificationRule(
            enabled = visitConfirmedEnabled,
            subjectTemplate = visitConfirmedSubjectTemplate,
            bodyTemplate = visitConfirmedBodyTemplate
        ),
        visitReadyForPickup = EmailNotificationRule(
            enabled = visitReadyForPickupEnabled,
            subjectTemplate = visitReadyForPickupSubjectTemplate,
            bodyTemplate = visitReadyForPickupBodyTemplate
        )
    )

    companion object {
        fun fromDomain(config: EmailAutomationConfig, existingId: UUID? = null): EmailAutomationConfigEntity =
            EmailAutomationConfigEntity(
                id = existingId ?: UUID.randomUUID(),
                studioId = config.studioId.value,
                visitWelcomeEnabled = config.visitWelcome.enabled,
                visitWelcomeSubjectTemplate = config.visitWelcome.subjectTemplate,
                visitWelcomeBodyTemplate = config.visitWelcome.bodyTemplate,
                visitConfirmedEnabled = config.visitConfirmed.enabled,
                visitConfirmedSubjectTemplate = config.visitConfirmed.subjectTemplate,
                visitConfirmedBodyTemplate = config.visitConfirmed.bodyTemplate,
                visitReadyForPickupEnabled = config.visitReadyForPickup.enabled,
                visitReadyForPickupSubjectTemplate = config.visitReadyForPickup.subjectTemplate,
                visitReadyForPickupBodyTemplate = config.visitReadyForPickup.bodyTemplate,
                updatedAt = Instant.now()
            )
    }
}
