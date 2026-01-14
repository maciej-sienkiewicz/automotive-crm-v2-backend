package pl.detailing.crm.appointmentcolor.update

import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class UpdateAppointmentColorCommand(
    val colorId: AppointmentColorId,
    val studioId: StudioId,
    val userId: UserId,
    val name: String,
    val hexColor: String
)
