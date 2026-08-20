package pl.detailing.crm.leads.intake

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** Jeden formularz na stronie studia = jeden adres webhooka. */
@Entity
@Table(
    name = "lead_intake_webhooks",
    indexes = [Index(name = "idx_lead_intake_webhooks_studio", columnList = "studio_id, created_at DESC")]
)
class LeadIntakeWebhookEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 120)
    var name: String,

    /** SHA-256 tokenu z adresu — samego tokenu nie przechowujemy. */
    @Column(name = "token_hash", nullable = false, length = 64)
    val tokenHash: String,

    @Column(name = "token_hint", nullable = false, length = 12)
    val tokenHint: String,

    @Column(name = "field_mapping", columnDefinition = "text")
    var fieldMapping: String? = null,

    @Column(name = "default_tag_codes", columnDefinition = "text")
    var defaultTagCodes: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_received_at")
    var lastReceivedAt: Instant? = null,

    @Column(name = "received_count", nullable = false)
    var receivedCount: Long = 0
)

/** Ślad każdego zgłoszenia razem z surowym ładunkiem — materiał do diagnozy. */
@Entity
@Table(
    name = "lead_intake_deliveries",
    indexes = [Index(name = "idx_lead_intake_deliveries_webhook", columnList = "webhook_id, received_at DESC")]
)
class LeadIntakeDeliveryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "webhook_id", nullable = false, columnDefinition = "uuid")
    val webhookId: UUID,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),

    /** CREATED / DUPLICATE / REJECTED — bez @Enumerated, żeby Hibernate nie dorobił CHECK-a. */
    @Column(name = "status", nullable = false, length = 20)
    val status: String,

    @Column(name = "reason", length = 300)
    val reason: String? = null,

    @Column(name = "lead_id", columnDefinition = "uuid")
    val leadId: UUID? = null,

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    val payload: String,

    @Column(name = "remote_ip", length = 64)
    val remoteIp: String? = null
)

@Repository
interface LeadIntakeWebhookRepository : JpaRepository<LeadIntakeWebhookEntity, UUID> {

    fun findByTokenHash(tokenHash: String): LeadIntakeWebhookEntity?

    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<LeadIntakeWebhookEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): LeadIntakeWebhookEntity?
}

@Repository
interface LeadIntakeDeliveryRepository : JpaRepository<LeadIntakeDeliveryEntity, UUID> {

    fun findTop20ByWebhookIdOrderByReceivedAtDesc(webhookId: UUID): List<LeadIntakeDeliveryEntity>

    /** Wykrywanie dubla: to samo zgłoszenie z tego samego formularza chwilę wcześniej. */
    fun existsByWebhookIdAndPayloadAndReceivedAtAfter(
        webhookId: UUID,
        payload: String,
        receivedAt: Instant
    ): Boolean
}
