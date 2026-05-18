package pl.detailing.crm.employee.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.employee.create.CreateEmployeeValidationContext
import pl.detailing.crm.shared.ConflictException

@Component
class EmployeeEmailUniquenessValidator {
    fun validate(context: CreateEmployeeValidationContext) {
        if (context.email != null && context.emailAlreadyExists) {
            throw ConflictException("Aktywny pracownik z adresem e-mail '${context.email}' już istnieje w tym studiu")
        }
    }
}
