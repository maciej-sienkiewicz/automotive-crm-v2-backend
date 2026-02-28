package pl.detailing.crm.statistics.category.manual

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Registry of unique manual service names per studio.
 *
 * When a visit is created with a service item that has no catalog serviceId
 * (visit_service_items.service_id IS NULL), the service is identified only by
 * its free-text name. This table assigns each unique (studio, name) pair a
 * stable, deterministic UUID so that:
 *
 *  - The breakdown endpoint can return a real serviceId for manual services.
 *  - The frontend can call the standard category assignment endpoints
 *    (POST /service-categories/{catId}/services/{serviceId}) using this UUID.
 *  - Manual services can be assigned to / unassigned from categories without
 *    any changes to existing API contracts.
 *
 * The UUID is derived deterministically via UUID.nameUUIDFromBytes so the same
 * studio + name always maps to the same UUID across app restarts.
 */
@Entity
@Table(
    name = "manual_services",
    indexes = [
        Index(name = "idx_manual_services_studio_id", columnList = "studio_id")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_manual_service_studio_name",
            columnNames = ["studio_id", "service_name"]
        )
    ]
)
class ManualServiceEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "service_name", nullable = false, length = 255)
    val serviceName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)
