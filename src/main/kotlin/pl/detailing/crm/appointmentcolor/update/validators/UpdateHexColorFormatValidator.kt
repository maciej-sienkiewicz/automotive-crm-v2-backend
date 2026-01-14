package pl.detailing.crm.appointmentcolor.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.update.UpdateAppointmentColorValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class UpdateHexColorFormatValidator {
    private val hexColorPattern = Regex("^#[0-9A-Fa-f]{6}$")

    fun validate(context: UpdateAppointmentColorValidationContext) {
        if (!hexColorPattern.matches(context.hexColor)) {
            throw ValidationException("Invalid hex color format. Expected format: #RRGGBB (e.g., #FF0000)")
        }
    }
}
