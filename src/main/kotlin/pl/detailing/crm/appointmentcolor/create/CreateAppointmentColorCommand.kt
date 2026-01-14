package pl.detailing.crm.appointmentcolor.create

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateAppointmentColorCommand(
    val studioId: StudioId,
    val userId: UserId,
    val name: String,
    val hexColor: String
)
