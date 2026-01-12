package pl.detailing.crm.service.update

import org.springframework.stereotype.Component
import pl.detailing.crm.service.update.validators.*

@Component
class UpdateServiceValidatorComposite(
    private val contextBuilder: UpdateServiceValidationContextBuilder,
    private val serviceExistenceValidator: ServiceExistenceValidator,
    private val serviceStatusValidator: ServiceStatusValidator,
    private val updateServiceNameValidator: UpdateServiceNameValidator,
    private val updatePriceValidator: UpdatePriceValidator,
    private val updateVatRateValidator: UpdateVatRateValidator
) {
    suspend fun validate(command: UpdateServiceCommand) {
        val context = contextBuilder.build(command)

        serviceExistenceValidator.validate(context)
        serviceStatusValidator.validate(context)
        updateServiceNameValidator.validate(context)
        updatePriceValidator.validate(context)
        updateVatRateValidator.validate(context)
    }
}