package pl.detailing.crm.appointmentcolor.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.create.CreateAppointmentColorValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class HexColorFormatValidator {
    private val hexColorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    fun validate(context: CreateAppointmentColorValidationContext) {
        if (!hexColorPattern.matches(context.hexColor)) {
            throw ValidationException("Invalid hex color format. Expected format: #RRGGBB (e.g., #FF0000)")
        }
    }
}
