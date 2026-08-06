package pl.detailing.crm.audit.infrastructure

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.shared.VisitId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Persistent form of an audit entry.
 *
 * Note on enum columns: every enum is mapped with an explicit `VARCHAR` [Column.columnDefinition]
 * so Hibernate's schema export does **not** generate a `CHECK (col IN (...))` constraint.
 * A generated CHECK freezes the enum in the database and makes every new constant an insert
 * failure until someone remembers a migration — the exact trap `V36__fix_all_enum_check_constraints`
 * was written to clean up. Validity of these values is the application's job.
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = [
        // Keyset pagination over the feed walks (studio_id, created_at DESC, id DESC).
        Index(name = "idx_audit_logs_studio_created_id", columnList = "studio_id, created_at DESC, id DESC"),
        Index(name = "idx_audit_logs_studio_created", columnList = "studio_id, created_at DESC"),
        Index(name = "idx_audit_logs_studio_module", columnList = "studio_id, module, created_at DESC"),
        Index(name = "idx_audit_logs_studio_entity", columnList = "studio_id, module, entity_id, created_at DESC"),
        Index(name = "idx_audit_logs_studio_action", columnList = "studio_id, action, created_at DESC"),
        Index(name = "idx_audit_logs_studio_user", columnList = "studio_id, user_id, created_at DESC"),
        // Drill-down: "everything that happened around this customer / vehicle / visit".
        Index(name = "idx_audit_logs_studio_customer", columnList = "studio_id, customer_id, created_at DESC"),
        Index(name = "idx_audit_logs_studio_vehicle", columnList = "studio_id, vehicle_id, created_at DESC"),
        Index(name = "idx_audit_logs_studio_visit", columnList = "studio_id, visit_id, created_at DESC"),
        Index(name = "idx_audit_logs_studio_actor_type", columnList = "studio_id, actor_type, created_at DESC"),
        Index(name = "idx_audit_logs_correlation", columnList = "correlation_id")
    ]
)
class AuditLogEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "actor_type", nullable = false, columnDefinition = "varchar(20)")
    @Enumerated(EnumType.STRING)
    val actorType: AuditActorType,

    /**
     * Identifier of the actor within the namespace implied by [actorType] — a user id for
     * employees, a customer id for customers, null for automated actors. Nullable since the
     * v2 model: before it, scheduled jobs wrote an all-zeroes UUID to satisfy NOT NULL.
     */
    @Column(name = "user_id", columnDefinition = "uuid")
    val userId: UUID?,

    /** Snapshot of the actor's name at the time of the action — deliberately denormalized. */
    @Column(name = "user_display_name", nullable = false, length = 200)
    val userDisplayName: String,

    @Column(name = "module", nullable = false, columnDefinition = "varchar(50)")
    @Enumerated(EnumType.STRING)
    val module: AuditModule,

    @Column(name = "entity_id", nullable = false, length = 100)
    val entityId: String,

    @Column(name = "entity_display_name", length = 500)
    val entityDisplayName: String?,

    @Column(name = "action", nullable = false, columnDefinition = "varchar(50)")
    @Enumerated(EnumType.STRING)
    val action: AuditAction,

    @Column(name = "severity", nullable = false, columnDefinition = "varchar(20)")
    @Enumerated(EnumType.STRING)
    val severity: AuditSeverity,

    @Column(name = "channel", nullable = false, columnDefinition = "varchar(30)")
    @Enumerated(EnumType.STRING)
    val channel: AuditChannel,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", columnDefinition = "jsonb")
    val changes: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    val metadata: String?,

    // ── Business context: indexed columns, not metadata keys ────────────────

    @Column(name = "customer_id", columnDefinition = "uuid")
    val customerId: UUID?,

    @Column(name = "customer_name", length = 300)
    val customerName: String?,

    @Column(name = "vehicle_id", columnDefinition = "uuid")
    val vehicleId: UUID?,

    @Column(name = "vehicle_name", length = 300)
    val vehicleName: String?,

    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID?,

    @Column(name = "visit_name", length = 300)
    val visitName: String?,

    @Column(name = "appointment_id", columnDefinition = "uuid")
    val appointmentId: UUID?,

    @Column(name = "appointment_name", length = 300)
    val appointmentName: String?,

    @Column(name = "amount_gross", precision = 19, scale = 2)
    val amountGross: BigDecimal?,

    @Column(name = "currency", length = 3)
    val currency: String?,

    @Column(name = "correlation_id", columnDefinition = "uuid")
    val correlationId: UUID?,

    /** Long enough for an IPv6 literal. */
    @Column(name = "ip_address", length = 45)
    val ipAddress: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(
        changesParser: (String?) -> List<FieldChange>,
        metadataParser: (String?) -> Map<String, String>
    ): AuditLog = AuditLog(
        id = AuditLogId(id),
        studioId = StudioId(studioId),
        actor = AuditActor(type = actorType, id = userId, displayName = userDisplayName),
        module = module,
        entityId = entityId,
        entityDisplayName = entityDisplayName,
        action = action,
        changes = changesParser(changes),
        metadata = metadataParser(metadata),
        context = AuditContext(
            customerId = customerId?.let { CustomerId(it) },
            customerName = customerName,
            vehicleId = vehicleId?.let { VehicleId(it) },
            vehicleName = vehicleName,
            visitId = visitId?.let { VisitId(it) },
            visitName = visitName,
            appointmentId = appointmentId?.let { AppointmentId(it) },
            appointmentName = appointmentName
        ),
        amount = amountGross?.let { AuditAmount(it, currency ?: AuditAmount.DEFAULT_CURRENCY) },
        severity = severity,
        channel = channel,
        ipAddress = ipAddress,
        correlationId = correlationId,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(
            auditLog: AuditLog,
            changesSerializer: (List<FieldChange>) -> String?,
            metadataSerializer: (Map<String, String>) -> String?
        ): AuditLogEntity = AuditLogEntity(
            id = auditLog.id.value,
            studioId = auditLog.studioId.value,
            actorType = auditLog.actor.type,
            userId = auditLog.actor.id,
            userDisplayName = auditLog.actor.displayName.take(200),
            module = auditLog.module,
            entityId = auditLog.entityId.take(100),
            entityDisplayName = auditLog.entityDisplayName?.take(500),
            action = auditLog.action,
            severity = auditLog.severity,
            channel = auditLog.channel,
            changes = changesSerializer(auditLog.changes),
            metadata = metadataSerializer(auditLog.metadata),
            customerId = auditLog.context.customerId?.value,
            customerName = auditLog.context.customerName?.take(300),
            vehicleId = auditLog.context.vehicleId?.value,
            vehicleName = auditLog.context.vehicleName?.take(300),
            visitId = auditLog.context.visitId?.value,
            visitName = auditLog.context.visitName?.take(300),
            appointmentId = auditLog.context.appointmentId?.value,
            appointmentName = auditLog.context.appointmentName?.take(300),
            amountGross = auditLog.amount?.value,
            currency = auditLog.amount?.currency,
            correlationId = auditLog.correlationId,
            ipAddress = auditLog.ipAddress?.take(45),
            createdAt = auditLog.createdAt
        )
    }
}
