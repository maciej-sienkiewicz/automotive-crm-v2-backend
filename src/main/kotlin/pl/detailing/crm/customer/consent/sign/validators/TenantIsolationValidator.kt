package pl.detailing.crm.customer.consent.sign.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.sign.SignConsentValidationContext
import pl.detailing.crm.shared.ValidationException

/**
 * Validates tenant isolation: ensures the template and customer belong to the same studio.
 */
@Component
class TenantIsolationValidator {

    fun validate(context: SignConsentValidationContext) {
        // Check if customer exists in this studio
        if (!context.customerExists) {
            throw ValidationException(
                "Customer with ID '${context.customerId}' does not exist in this studio"
            )
        }

        // Check if template belongs to this studio
        if (context.template != null && context.template.studioId != context.studioId) {
            throw ValidationException(
                "Consent template does not belong to this studio. Possible data isolation breach."
            )
        }
    }
}
