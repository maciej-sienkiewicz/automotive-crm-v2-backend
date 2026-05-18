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
                "Klient o ID '${context.customerId}' nie istnieje w tym studiu"
            )
        }

        // Check if template belongs to this studio
        if (context.template != null && context.template.studioId != context.studioId) {
            throw ValidationException(
                "Szablon zgody nie należy do tego studia. Możliwe naruszenie izolacji danych."
            )
        }
    }
}
