package pl.detailing.crm.appointment.recurrence.create

import pl.detailing.crm.appointment.create.CreateAppointmentCommand
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceEndType
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceSeriesId
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceType
import pl.detailing.crm.shared.*
import java.time.DayOfWeek
import java.time.LocalDate

data class CreateRecurringAppointmentCommand(
    val base: CreateAppointmentCommand,
    val recurrenceRule: RecurrenceRuleCommand
)

data class RecurrenceRuleCommand(
    val type: RecurrenceType,
    val intervalWeeks: Int? = null,
    val daysOfWeek: Set<DayOfWeek>? = null,
    val dayOfMonth: Int? = null,
    val endType: RecurrenceEndType,
    val endDate: LocalDate? = null,
    val maxOccurrences: Int? = null
)

data class CreateRecurringAppointmentResult(
    val seriesId: RecurrenceSeriesId,
    val occurrenceCount: Int,
    val firstAppointmentId: AppointmentId,
    val customerId: CustomerId,
    val vehicleId: VehicleId?
)
