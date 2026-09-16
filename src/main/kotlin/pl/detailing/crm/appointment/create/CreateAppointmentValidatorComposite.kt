package pl.detailing.crm.appointment.create

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.validators.*

@Component
class CreateAppointmentValidatorComposite(
    private val contextBuilder: CreateAppointmentValidationContextBuilder,
    private val customerExistenceValidator: CustomerExistenceValidator,
    private val vehicleExistenceValidator: VehicleExistenceValidator,
    private val appointmentColorValidator: AppointmentColorValidator,
    private val newCustomerUniquenessValidator: NewCustomerUniquenessValidator,
    private val customerContactInfoValidator: CustomerContactInfoValidator,
    private val lineItemVatRateValidator: LineItemVatRateValidator,
    private val manualPriceRequiredValidator: ManualPriceRequiredValidator
) {
    suspend fun validate(command: CreateAppointmentCommand) {
        val context = contextBuilder.build(command)

        // Run validators in order
        customerContactInfoValidator.validate(context)
        appointmentColorValidator.validate(context)
        customerExistenceValidator.validate(context)
        vehicleExistenceValidator.validate(context)
        newCustomerUniquenessValidator.validate(context)
        lineItemVatRateValidator.validate(context)
        // Podpięty dopiero teraz. Klasa istniała od dawna, ale nie była wołana z żadnego
        // miejsca - więc pozycja bez ceny przechodziła bez słowa i zapisywała się jako 0 zł.
        manualPriceRequiredValidator.validate(context)
    }
}
