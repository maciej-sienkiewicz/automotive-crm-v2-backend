package pl.detailing.crm.visit.services.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.services.AddServiceValidationContext

@Component
class ServiceIsActiveValidator {
    fun validate(context: AddServiceValidationContext) {
        if (context.service?.isActive == false) {
            throw ValidationException("Service ${context.serviceId} is not active")
        }
    }
}
