package pl.detailing.crm.appointmentcolor.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.create.CreateAppointmentColorValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ColorNameValidator {
    fun validate(context: CreateAppointmentColorValidationContext) {
        if (context.name.isBlank()) {
            throw ValidationException("Color name cannot be empty")
        }

        if (context.name.length > 100) {
            throw ValidationException("Color name cannot exceed 100 characters")
        }

        if (context.nameAlreadyExists) {
            throw ValidationException("A color with this name already exists in your studio")
        }
    }
}
