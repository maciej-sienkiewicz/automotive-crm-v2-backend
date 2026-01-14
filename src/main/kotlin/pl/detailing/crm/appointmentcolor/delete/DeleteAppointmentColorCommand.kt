package pl.detailing.crm.appointmentcolor.delete

import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.StudioId

data class DeleteAppointmentColorCommand(
    val colorId: AppointmentColorId,
    val studioId: StudioId
)
