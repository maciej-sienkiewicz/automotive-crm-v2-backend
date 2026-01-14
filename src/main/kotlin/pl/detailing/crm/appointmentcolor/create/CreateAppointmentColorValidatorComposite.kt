package pl.detailing.crm.appointmentcolor.create

import org.springframework.stereotype.Component
import pl.detailing.crm.appointmentcolor.create.validators.ColorNameValidator
import pl.detailing.crm.appointmentcolor.create.validators.HexColorFormatValidator

@Component
class CreateAppointmentColorValidatorComposite(
    private val contextBuilder: CreateAppointmentColorValidationContextBuilder,
    private val colorNameValidator: ColorNameValidator,
    private val hexColorFormatValidator: HexColorFormatValidator
) {
    suspend fun validate(command: CreateAppointmentColorCommand) {
        val context = contextBuilder.build(command)

        colorNameValidator.validate(context)
        hexColorFormatValidator.validate(context)
    }
}
