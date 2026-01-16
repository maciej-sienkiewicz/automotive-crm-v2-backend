package pl.detailing.crm.visit.services.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.visit.services.UpdateServiceStatusValidationContext

@Component
class ServiceItemExistsValidator {
    fun validate(context: UpdateServiceStatusValidationContext) {
        if (context.visit == null) {
            throw EntityNotFoundException("Visit with ID ${context.visitId} not found")
        }

        if (context.serviceItem == null) {
            throw EntityNotFoundException(
                "Service item with ID ${context.serviceItemId} not found in visit ${context.visitId}"
            )
        }
    }
}
