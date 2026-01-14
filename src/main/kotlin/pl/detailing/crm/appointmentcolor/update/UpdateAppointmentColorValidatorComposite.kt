package pl.detailing.crm.appointmentcolor.update

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.update.validators.ColorExistenceValidator
import pl.detailing.crm.appointmentcolor.update.validators.UpdateColorNameValidator
import pl.detailing.crm.appointmentcolor.update.validators.UpdateHexColorFormatValidator

@Component
class UpdateAppointmentColorValidatorComposite(
    private val contextBuilder: UpdateAppointmentColorValidationContextBuilder,
    private val colorExistenceValidator: ColorExistenceValidator,
    private val updateColorNameValidator: UpdateColorNameValidator,
    private val updateHexColorFormatValidator: UpdateHexColorFormatValidator
) {
    suspend fun validate(command: UpdateAppointmentColorCommand) {
        val context = contextBuilder.build(command)

        colorExistenceValidator.validate(context)
        updateColorNameValidator.validate(context)
        updateHexColorFormatValidator.validate(context)
    }
}
