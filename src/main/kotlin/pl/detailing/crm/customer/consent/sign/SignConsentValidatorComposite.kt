package pl.detailing.crm.customer.consent.sign

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.sign.validators.TemplateExistenceValidator
import pl.detailing.crm.customer.consent.sign.validators.TenantIsolationValidator

/**
 * Composite validator for signing a consent.
 * Runs all validation checks in sequence.
 */
@Component
class SignConsentValidatorComposite(
    private val contextBuilder: SignConsentValidationContextBuilder,
    private val templateExistenceValidator: TemplateExistenceValidator,
    private val tenantIsolationValidator: TenantIsolationValidator
) {

    suspend fun validate(command: SignConsentCommand) {
        val context = contextBuilder.build(command)

        // Run validators in order
        templateExistenceValidator.validate(context)
        tenantIsolationValidator.validate(context)
    }
}
