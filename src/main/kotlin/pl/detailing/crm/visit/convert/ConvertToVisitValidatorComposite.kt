package pl.detailing.crm.visit.convert

import org.springframework.stereotype.Component
import pl.detailing.crm.visit.convert.validators.*

@Component
class ConvertToVisitValidatorComposite(
    private val contextBuilder: ConvertToVisitValidationContextBuilder,
    private val appointmentExistsValidator: AppointmentExistsValidator,
    private val vehicleExistsValidator: VehicleExistsValidator,
    private val customerExistsValidator: CustomerExistsValidator,
    private val visitNotAlreadyExistsValidator: VisitNotAlreadyExistsValidator,
    private val appointmentHasServicesValidator: AppointmentHasServicesValidator
) {
    suspend fun validate(command: ConvertToVisitCommand): ConvertToVisitValidationContext {
        val context = contextBuilder.build(command)

        // Run validators in order
        appointmentExistsValidator.validate(context)
        vehicleExistsValidator.validate(context)
        customerExistsValidator.validate(context)
        visitNotAlreadyExistsValidator.validate(context)
        appointmentHasServicesValidator.validate(context)

        return context
    }
}
