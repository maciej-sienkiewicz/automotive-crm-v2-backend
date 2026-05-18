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
                "Szablon zgody o ID '${context.templateId}' nie istnieje lub jest niedostępny"
            )
        }

        if (!context.template.isActive) {
            throw ValidationException(
                "Wersja ${context.template.version} szablonu zgody nie jest już aktywna. " +
                "Proszę użyć aktualnej aktywnej wersji."
            )
        }
    }
}
