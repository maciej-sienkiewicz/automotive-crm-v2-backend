package pl.detailing.crm.visit.services

import org.springframework.stereotype.Component
import pl.detailing.crm.visit.services.validators.*

@Component
class AddServiceValidatorComposite(
    private val contextBuilder: AddServiceValidationContextBuilder,
    private val visitExistsValidator: VisitExistsValidator,
    private val serviceExistsValidator: ServiceExistsValidator,
    private val serviceIsActiveValidator: ServiceIsActiveValidator
) {
    suspend fun validate(command: AddServiceCommand): AddServiceValidationContext {
        val context = contextBuilder.build(command)

        // Run validators
        visitExistsValidator.validate(context)
        serviceExistsValidator.validate(context)
        serviceIsActiveValidator.validate(context)

        return context
    }
}
