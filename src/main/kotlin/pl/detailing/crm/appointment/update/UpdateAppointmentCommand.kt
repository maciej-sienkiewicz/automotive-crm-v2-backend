package pl.detailing.crm.appointment.update

import pl.detailing.crm.appointment.create.*
import pl.detailing.crm.shared.*

data class UpdateAppointmentCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val customer: CustomerIdentity,
    val vehicle: VehicleIdentity,
    val services: List<ServiceLineItemCommand>,
    val schedule: ScheduleCommand,
    val appointmentTitle: String?,
    val appointmentColorId: AppointmentColorId,
    val note: String?
)

fun UpdateAppointmentCommand.toCreateCommand() = CreateAppointmentCommand(
    studioId = studioId,
    userId = userId,
    customer = customer,
    vehicle = vehicle,
    services = services,
    schedule = schedule,
    appointmentTitle = appointmentTitle,
    appointmentColorId = appointmentColorId,
    note = note
)
