package pl.detailing.crm.employee.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.employee.create.CreateEmployeeValidationContext
import pl.detailing.crm.shared.ConflictException

@Component
class EmployeeEmailUniquenessValidator {
    fun validate(context: CreateEmployeeValidationContext) {
        if (context.email != null && context.emailAlreadyExists) {
            throw ConflictException("An active employee with email '${context.email}' already exists in this studio")
        }
    }
}
