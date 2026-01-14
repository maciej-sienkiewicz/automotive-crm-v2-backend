package pl.detailing.crm.appointmentcolor.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.update.UpdateAppointmentColorValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class UpdateColorNameValidator {
    fun validate(context: UpdateAppointmentColorValidationContext) {
        if (context.name.isBlank()) {
            throw ValidationException("Color name cannot be empty")
        }

        if (context.name.length > 100) {
            throw ValidationException("Color name cannot exceed 100 characters")
        }

        if (context.nameAlreadyExistsInOtherColor) {
            throw ValidationException("A color with this name already exists in your studio")
        }
    }
}
