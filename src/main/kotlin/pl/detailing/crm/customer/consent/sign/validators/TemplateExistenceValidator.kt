package pl.detailing.crm.customer.consent.sign.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.sign.SignConsentValidationContext
import pl.detailing.crm.shared.ValidationException

/**
 * Validates that the consent template exists and is active.
 */
@Component
class TemplateExistenceValidator {

    fun validate(context: SignConsentValidationContext) {
        if (context.template == null) {
            throw ValidationException(
                "Consent template with ID '${context.templateId}' does not exist or is not accessible"
            )
        }

        if (!context.template.isActive) {
            throw ValidationException(
                "Consent template version ${context.template.version} is no longer active. " +
                "Please use the current active version."
            )
        }
    }
}
