package pl.detailing.crm.inbound.accept

import org.springframework.stereotype.Component
import pl.detailing.crm.inbound.accept.validators.CallAlreadyProcessedValidator
import pl.detailing.crm.inbound.accept.validators.CallExistsValidator

@Component
class AcceptCallValidatorComposite(
    private val contextBuilder: AcceptCallValidationContextBuilder,
    private val callExistsValidator: CallExistsValidator,
    private val callAlreadyProcessedValidator: CallAlreadyProcessedValidator
) {
    suspend fun validate(command: AcceptCallCommand): AcceptCallValidationContext {
        val context = contextBuilder.build(command)

        callExistsValidator.validate(context)
        callAlreadyProcessedValidator.validate(context)

        return context
    }
}
