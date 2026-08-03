package pl.detailing.crm.campaigns.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pl.detailing.crm.campaigns.domain.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

private val json: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

@Entity
@Table(
    name = "campaigns",
    indexes = [
        Index(name = "idx_campaigns_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_campaigns_due", columnList = "status, scheduled_at")
    ]
)
class CampaignEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    val kind: CampaignKind,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    var channel: CampaignChannel,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CampaignStatus,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience", nullable = false, columnDefinition = "jsonb")
    var audienceJson: String,

    @Column(name = "sms_template", columnDefinition = "TEXT")
    var smsTemplate: String?,

    @Column(name = "email_subject", columnDefinition = "TEXT")
    var emailSubject: String?,

    @Column(name = "email_body", columnDefinition = "TEXT")
    var emailBody: String?,

    @Column(name = "scheduled_at", columnDefinition = "timestamp with time zone")
    var scheduledAt: Instant?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_config", columnDefinition = "jsonb")
    var triggerJson: String?,

    @Column(name = "recipients_total", nullable = false)
    var recipientsTotal: Int = 0,

    @Column(name = "recipients_sent", nullable = false)
    var recipientsSent: Int = 0,

    @Column(name = "recipients_failed", nullable = false)
    var recipientsFailed: Int = 0,

    @Column(name = "recipients_skipped", nullable = false)
    var recipientsSkipped: Int = 0,

    @Column(name = "credits_spent", nullable = false)
    var creditsSpent: Int = 0,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant,

    @Column(name = "started_at", columnDefinition = "timestamp with time zone")
    var startedAt: Instant?,

    @Column(name = "completed_at", columnDefinition = "timestamp with time zone")
    var completedAt: Instant?
) {
    fun toDomain(): Campaign = Campaign(
        id = id,
        studioId = StudioId(studioId),
        name = name,
        kind = kind,
        channel = channel,
        status = status,
        audience = json.readValue(audienceJson),
        smsTemplate = smsTemplate,
        emailSubject = emailSubject,
        emailBody = emailBody,
        scheduledAt = scheduledAt,
        trigger = triggerJson?.let { json.readValue(it) },
        recipientsTotal = recipientsTotal,
        recipientsSent = recipientsSent,
        recipientsFailed = recipientsFailed,
        recipientsSkipped = recipientsSkipped,
        creditsSpent = creditsSpent,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt,
        startedAt = startedAt,
        completedAt = completedAt
    )

    companion object {
        fun fromDomain(c: Campaign): CampaignEntity = CampaignEntity(
            id = c.id,
            studioId = c.studioId.value,
            name = c.name,
            kind = c.kind,
            channel = c.channel,
            status = c.status,
            audienceJson = json.writeValueAsString(c.audience),
            smsTemplate = c.smsTemplate,
            emailSubject = c.emailSubject,
            emailBody = c.emailBody,
            scheduledAt = c.scheduledAt,
            triggerJson = c.trigger?.let { json.writeValueAsString(it) },
            recipientsTotal = c.recipientsTotal,
            recipientsSent = c.recipientsSent,
            recipientsFailed = c.recipientsFailed,
            recipientsSkipped = c.recipientsSkipped,
            creditsSpent = c.creditsSpent,
            createdBy = c.createdBy.value,
            updatedBy = c.updatedBy.value,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
            startedAt = c.startedAt,
            completedAt = c.completedAt
        )
    }
}

@Entity
@Table(
    name = "campaign_recipients",
    indexes = [
        Index(name = "idx_campaign_recipients_due", columnList = "status, scheduled_for"),
        Index(name = "idx_campaign_recipients_campaign", columnList = "campaign_id, status"),
        Index(name = "idx_campaign_recipients_customer", columnList = "studio_id, customer_id")
    ]
)
class CampaignRecipientEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "campaign_id", nullable = false, columnDefinition = "uuid")
    val campaignId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    val channel: RecipientChannel,

    @Column(name = "address", nullable = false, length = 255)
    val address: String,

    @Column(name = "rendered_subject", columnDefinition = "TEXT")
    val renderedSubject: String?,

    @Column(name = "rendered_body", nullable = false, columnDefinition = "TEXT")
    var renderedBody: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: RecipientStatus,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String?,

    @Column(name = "trigger_source_visit_id", columnDefinition = "uuid")
    val triggerSourceVisitId: UUID?,

    @Column(name = "scheduled_for", nullable = false, columnDefinition = "timestamp with time zone")
    var scheduledFor: Instant,

    @Column(name = "sent_at", columnDefinition = "timestamp with time zone")
    var sentAt: Instant?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant
) {
    fun toDomain(): CampaignRecipient = CampaignRecipient(
        id = id,
        campaignId = campaignId,
        studioId = studioId,
        customerId = customerId,
        channel = channel,
        address = address,
        renderedSubject = renderedSubject,
        renderedBody = renderedBody,
        status = status,
        errorMessage = errorMessage,
        triggerSourceVisitId = triggerSourceVisitId,
        scheduledFor = scheduledFor,
        sentAt = sentAt,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(r: CampaignRecipient): CampaignRecipientEntity = CampaignRecipientEntity(
            id = r.id,
            campaignId = r.campaignId,
            studioId = r.studioId,
            customerId = r.customerId,
            channel = r.channel,
            address = r.address,
            renderedSubject = r.renderedSubject,
            renderedBody = r.renderedBody,
            status = r.status,
            errorMessage = r.errorMessage,
            triggerSourceVisitId = r.triggerSourceVisitId,
            scheduledFor = r.scheduledFor,
            sentAt = r.sentAt,
            createdAt = r.createdAt
        )
    }
}

@Entity
@Table(name = "campaign_settings")
class CampaignSettingsEntity(
    @Id
    @Column(name = "studio_id", columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "quiet_hours_start", nullable = false)
    var quietHoursStart: java.time.LocalTime,

    @Column(name = "quiet_hours_end", nullable = false)
    var quietHoursEnd: java.time.LocalTime,

    @Column(name = "frequency_cap_days", nullable = false)
    var frequencyCapDays: Int,

    @Column(name = "sms_footer", columnDefinition = "TEXT")
    var smsFooter: String?,

    @Column(name = "email_footer", columnDefinition = "TEXT")
    var emailFooter: String?,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant
) {
    fun toDomain() = CampaignSettings(
        studioId = StudioId(studioId),
        quietHoursStart = quietHoursStart,
        quietHoursEnd = quietHoursEnd,
        frequencyCapDays = frequencyCapDays,
        smsFooter = smsFooter,
        emailFooter = emailFooter,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(s: CampaignSettings) = CampaignSettingsEntity(
            studioId = s.studioId.value,
            quietHoursStart = s.quietHoursStart,
            quietHoursEnd = s.quietHoursEnd,
            frequencyCapDays = s.frequencyCapDays,
            smsFooter = s.smsFooter,
            emailFooter = s.emailFooter,
            updatedAt = s.updatedAt
        )
    }
}

@Entity
@Table(
    name = "campaign_opt_outs",
    uniqueConstraints = [UniqueConstraint(name = "uq_campaign_opt_out", columnNames = ["studio_id", "customer_id"])]
)
class CampaignOptOutEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    val source: OptOutSource,

    @Column(name = "opted_out_at", nullable = false, columnDefinition = "timestamp with time zone")
    val optedOutAt: Instant
)
