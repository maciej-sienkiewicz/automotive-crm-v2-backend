package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ScheduleConflictValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        if (context.overlappingAppointments.isNotEmpty()) {
            val overlappingAppointment = context.overlappingAppointments.first()
            throw ValidationException(
                "Schedule conflict: An appointment is already scheduled during the selected time range " +
                "(${context.schedule.startDateTime} - ${context.schedule.endDateTime}). " +
                "Overlapping appointment ID: ${overlappingAppointment.id}"
            )
        }
    }
}
