package pl.detailing.crm.service.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.service.update.UpdateServiceValidationContext
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException

@Component
class ServiceExistenceValidator {
    fun validate(context: UpdateServiceValidationContext) {
        if (context.oldService == null) {
            throw EntityNotFoundException(
                "Service with ID ${context.oldServiceId} not found in this studio"
            )
        }

        if (context.oldService.studioId != context.studioId.value) {
            throw ForbiddenException("Service does not belong to this studio")
        }
    }
}
