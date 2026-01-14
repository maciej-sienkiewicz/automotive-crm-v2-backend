package pl.detailing.crm.appointment.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "appointment_colors",
    indexes = [
        Index(name = "idx_appointment_colors_studio_id", columnList = "studio_id"),
        Index(name = "idx_appointment_colors_studio_active", columnList = "studio_id, is_active"),
        Index(name = "idx_appointment_colors_created_by", columnList = "created_by"),
        Index(name = "idx_appointment_colors_updated_by", columnList = "updated_by")
    ]
)
class AppointmentColorEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "hex_color", nullable = false, length = 7)
    var hexColor: String,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    data class AppointmentColorDomain(
        val id: AppointmentColorId,
        val studioId: StudioId,
        val name: String,
        val hexColor: String,
        val isActive: Boolean,
        val createdBy: UserId,
        val updatedBy: UserId,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    fun toDomain(): AppointmentColorDomain = AppointmentColorDomain(
        id = AppointmentColorId(id),
        studioId = StudioId(studioId),
        name = name,
        hexColor = hexColor,
        isActive = isActive,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(domain: AppointmentColorDomain): AppointmentColorEntity = AppointmentColorEntity(
            id = domain.id.value,
            studioId = domain.studioId.value,
            name = domain.name,
            hexColor = domain.hexColor,
            isActive = domain.isActive,
            createdBy = domain.createdBy.value,
            updatedBy = domain.updatedBy.value,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt
        )
    }
}
