package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.shared.EntityNotFoundException

@Component
class AppointmentColorValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        if (!context.appointmentColorExists) {
            throw EntityNotFoundException(
                "Appointment color not found in this studio"
            )
        }
    }
}
