package pl.detailing.crm.appointmentcolor.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.create.CreateAppointmentColorValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ColorNameValidator {
    fun validate(context: CreateAppointmentColorValidationContext) {
        if (context.name.isBlank()) {
            throw ValidationException("Nazwa koloru nie może być pusta")
        }

        if (context.name.length > 100) {
            throw ValidationException("Nazwa koloru nie może przekraczać 100 znaków")
        }

        if (context.nameAlreadyExists) {
            throw ValidationException("Kolor o tej nazwie już istnieje w Twoim studiu")
        }
    }
}
