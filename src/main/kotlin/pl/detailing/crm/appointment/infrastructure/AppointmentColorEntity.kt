package pl.detailing.crm.appointment.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.StudioId
import java.util.UUID

@Entity
@Table(
    name = "appointment_colors",
    indexes = [
        Index(name = "idx_appointment_colors_studio_id", columnList = "studio_id")
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
    var hexColor: String
) {
    data class AppointmentColorDomain(
        val id: AppointmentColorId,
        val studioId: StudioId,
        val name: String,
        val hexColor: String
    )

    fun toDomain(): AppointmentColorDomain = AppointmentColorDomain(
        id = AppointmentColorId(id),
        studioId = StudioId(studioId),
        name = name,
        hexColor = hexColor
    )
}
