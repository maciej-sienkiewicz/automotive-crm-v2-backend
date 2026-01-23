package pl.detailing.crm.appointment.create

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.validators.*

@Component
class CreateAppointmentValidatorComposite(
    private val contextBuilder: CreateAppointmentValidationContextBuilder,
    private val serviceAvailabilityValidator: ServiceAvailabilityValidator,
    private val customerExistenceValidator: CustomerExistenceValidator,
    private val vehicleExistenceValidator: VehicleExistenceValidator,
    private val appointmentColorValidator: AppointmentColorValidator,
    private val newCustomerUniquenessValidator: NewCustomerUniquenessValidator,
    private val manualPriceRequiredValidator: ManualPriceRequiredValidator
) {
    suspend fun validate(command: CreateAppointmentCommand) {
        val context = contextBuilder.build(command)

        // Run validators in order
        serviceAvailabilityValidator.validate(context)
        manualPriceRequiredValidator.validate(context)
        appointmentColorValidator.validate(context)
        customerExistenceValidator.validate(context)
        vehicleExistenceValidator.validate(context)
        newCustomerUniquenessValidator.validate(context)
    }
}
