package pl.detailing.crm.appointment.recurrence

import pl.detailing.crm.appointment.create.CreateAppointmentRequest
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceEndType
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceType
import java.time.DayOfWeek
import java.time.LocalDate

data class CreateRecurringAppointmentRequest(
    val customer: pl.detailing.crm.appointment.create.CustomerIdentityRequest,
    val vehicle: pl.detailing.crm.appointment.create.VehicleIdentityRequest,
    val services: List<pl.detailing.crm.appointment.create.ServiceLineItemRequest>,
    val schedule: pl.detailing.crm.appointment.create.ScheduleRequest,
    val appointmentTitle: String?,
    val appointmentColorId: String,
    val note: String?,
    val sendConfirmationSms: Boolean = false,
    val sendReminderSms: Boolean = false,
    val recurrence: RecurrenceRuleRequest
) {
    fun toBaseRequest() = CreateAppointmentRequest(
        customer = customer,
        vehicle = vehicle,
        services = services,
        schedule = schedule,
        appointmentTitle = appointmentTitle,
        appointmentColorId = appointmentColorId,
        note = note,
        sendConfirmationSms = sendConfirmationSms,
        sendReminderSms = sendReminderSms
    )
}

data class RecurrenceRuleRequest(
    val type: RecurrenceType,
    val intervalWeeks: Int? = null,
    val daysOfWeek: List<DayOfWeek>? = null,
    val dayOfMonth: Int? = null,
    val endType: RecurrenceEndType,
    val endDate: LocalDate? = null,
    val maxOccurrences: Int? = null
)

data class CreateRecurringAppointmentResponse(
    val seriesId: String,
    val occurrenceCount: Int,
    val firstAppointmentId: String,
    val customerId: String,
    val vehicleId: String?
)
