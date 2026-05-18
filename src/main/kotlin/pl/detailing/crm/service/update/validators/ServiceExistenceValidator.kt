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
                "Usługa o ID ${context.oldServiceId} nie została znaleziona w tym studiu"
            )
        }

        if (context.oldService.studioId != context.studioId.value) {
            throw ForbiddenException("Usługa nie należy do tego studia")
        }
    }
}
