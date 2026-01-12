package pl.detailing.crm.service.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.service.create.CreateServiceValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ServiceNameValidator {
    fun validate(context: CreateServiceValidationContext) {
        val name = context.name.trim()

        if (name.isBlank()) {
            throw ValidationException("Service name cannot be blank")
        }

        if (name.length < 3) {
            throw ValidationException("Service name must be at least 3 characters long")
        }

        if (name.length > 200) {
            throw ValidationException("Service name cannot exceed 200 characters")
        }

        if (context.serviceNameExists) {
            throw ValidationException("Service with name '$name' already exists in this studio")
        }
    }
}