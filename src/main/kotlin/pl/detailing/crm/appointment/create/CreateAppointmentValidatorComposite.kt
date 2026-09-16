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
    private val lineItemVatRateValidator: LineItemVatRateValidator
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
        // Świadomie NIE walidujemy, czy usługa z ceną ustalaną ręcznie ma cenę różną od
        // zera. Cena 0 jest legalna: część usług bywa darmowa (gratis, gest, dorzucone do
        // pakietu). Właściwą obroną przed zgubieniem ceny jest to, że handler bierze kwotę
        // z żądania (catalogBaseNet/catalogBaseGross), a nie zero z cennika - nie walidator,
        // który nie odróżnia „użytkownik chciał 0" od „użytkownik nie wpisał nic".
    }
}
