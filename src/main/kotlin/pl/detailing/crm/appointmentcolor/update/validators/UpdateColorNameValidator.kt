package pl.detailing.crm.appointmentcolor.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.update.UpdateAppointmentColorValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class UpdateColorNameValidator {
    fun validate(context: UpdateAppointmentColorValidationContext) {
        if (context.name.isBlank()) {
            throw ValidationException("Nazwa koloru nie może być pusta")
        }

        if (context.name.length > 100) {
            throw ValidationException("Nazwa koloru nie może przekraczać 100 znaków")
        }

        if (context.nameAlreadyExistsInOtherColor) {
            throw ValidationException("Kolor o tej nazwie już istnieje w Twoim studiu")
        }
    }
}
