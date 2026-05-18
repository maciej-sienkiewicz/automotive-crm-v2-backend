package pl.detailing.crm.service.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.service.update.UpdateServiceValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class UpdateServiceNameValidator {
    fun validate(context: UpdateServiceValidationContext) {
        val name = context.name.trim()

        if (name.isBlank()) {
            throw ValidationException("Nazwa usługi nie może być pusta")
        }

        if (name.length < 3) {
            throw ValidationException("Nazwa usługi musi mieć co najmniej 3 znaki")
        }

        if (name.length > 200) {
            throw ValidationException("Nazwa usługi nie może przekraczać 200 znaków")
        }

        if (context.nameConflictExists) {
            throw ValidationException(
                "Inna aktywna usługa o nazwie '$name' już istnieje w tym studiu"
            )
        }
    }
}