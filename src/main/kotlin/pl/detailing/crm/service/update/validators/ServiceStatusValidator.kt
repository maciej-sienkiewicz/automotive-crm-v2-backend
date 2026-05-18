package pl.detailing.crm.service.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.service.update.UpdateServiceValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ServiceStatusValidator {
    fun validate(context: UpdateServiceValidationContext) {
        if (context.oldService != null && !context.oldService.isActive) {
            throw ValidationException(
                "Nie można aktualizować zarchiwizowanej usługi. Usługa ${context.oldServiceId} nie jest już aktywna"
            )
        }
    }
}