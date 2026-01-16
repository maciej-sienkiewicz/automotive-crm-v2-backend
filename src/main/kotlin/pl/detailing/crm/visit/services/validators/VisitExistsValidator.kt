package pl.detailing.crm.visit.services.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.visit.services.AddServiceValidationContext

@Component
class VisitExistsValidator {
    fun validate(context: AddServiceValidationContext) {
        if (context.visit == null) {
            throw EntityNotFoundException("Visit with ID ${context.visitId} not found")
        }
    }
}
