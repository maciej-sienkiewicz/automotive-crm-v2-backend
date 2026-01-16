package pl.detailing.crm.visit.convert.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.visit.convert.ConvertToVisitValidationContext

@Component
class AppointmentExistsValidator {
    fun validate(context: ConvertToVisitValidationContext) {
        if (context.appointment == null) {
            throw EntityNotFoundException(
                "Appointment with ID ${context.appointmentId} not found"
            )
        }
    }
}
