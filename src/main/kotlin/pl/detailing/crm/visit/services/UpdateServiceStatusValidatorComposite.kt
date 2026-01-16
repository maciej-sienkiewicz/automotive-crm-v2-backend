package pl.detailing.crm.visit.services

import org.springframework.stereotype.Component
import pl.detailing.crm.visit.services.validators.ServiceItemExistsValidator

@Component
class UpdateServiceStatusValidatorComposite(
    private val contextBuilder: UpdateServiceStatusValidationContextBuilder,
    private val serviceItemExistsValidator: ServiceItemExistsValidator
) {
    suspend fun validate(command: UpdateServiceStatusCommand): UpdateServiceStatusValidationContext {
        val context = contextBuilder.build(command)

        // Run validators
        serviceItemExistsValidator.validate(context)

        return context
    }
}
