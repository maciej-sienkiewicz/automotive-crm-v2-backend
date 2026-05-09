package pl.detailing.crm.appointment.lead

import pl.detailing.crm.appointment.create.CustomerIdentity
import pl.detailing.crm.appointment.create.ScheduleCommand
import pl.detailing.crm.appointment.create.ServiceLineItemCommand
import pl.detailing.crm.appointment.create.VehicleIdentity
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class CreateLeadAppointmentCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val customer: CustomerIdentity,
    val vehicle: VehicleIdentity,
    val services: List<ServiceLineItemCommand>,
    val schedule: ScheduleCommand,
    val appointmentTitle: String?,
    val appointmentColorId: AppointmentColorId,
    val note: String?,
    val sendReminderSms: Boolean = false
)
