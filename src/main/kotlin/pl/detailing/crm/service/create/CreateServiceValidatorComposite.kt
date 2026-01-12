package pl.detailing.crm.service.create

import org.springframework.stereotype.Component
import pl.detailing.crm.service.create.validators.PriceValidator
import pl.detailing.crm.service.create.validators.ServiceNameValidator
import pl.detailing.crm.service.create.validators.VatRateValidator

@Component
class CreateServiceValidatorComposite(
    private val contextBuilder: CreateServiceValidationContextBuilder,
    private val serviceNameValidator: ServiceNameValidator,
    private val priceValidator: PriceValidator,
    private val vatRateValidator: VatRateValidator
) {
    suspend fun validate(command: CreateServiceCommand) {
        val context = contextBuilder.build(command)

        serviceNameValidator.validate(context)
        priceValidator.validate(context)
        vatRateValidator.validate(context)
    }
}