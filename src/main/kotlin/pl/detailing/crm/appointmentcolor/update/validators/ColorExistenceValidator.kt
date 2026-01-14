package pl.detailing.crm.appointmentcolor.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.update.UpdateAppointmentColorValidationContext
import pl.detailing.crm.shared.EntityNotFoundException

@Component
class ColorExistenceValidator {
    fun validate(context: UpdateAppointmentColorValidationContext) {
        if (!context.colorExists) {
            throw EntityNotFoundException("Appointment color not found in this studio")
        }
    }
}
