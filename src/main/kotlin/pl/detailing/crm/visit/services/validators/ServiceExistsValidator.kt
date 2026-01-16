package pl.detailing.crm.visit.services.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.visit.services.AddServiceValidationContext

@Component
class ServiceExistsValidator {
    fun validate(context: AddServiceValidationContext) {
        if (context.service == null) {
            throw EntityNotFoundException("Service with ID ${context.serviceId} not found")
        }
    }
}
