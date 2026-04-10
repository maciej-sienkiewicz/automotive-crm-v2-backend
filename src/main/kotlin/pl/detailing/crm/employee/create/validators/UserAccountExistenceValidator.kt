package pl.detailing.crm.employee.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.employee.create.CreateEmployeeValidationContext
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException

@Component
class UserAccountExistenceValidator {
    fun validate(context: CreateEmployeeValidationContext) {
        if (context.linkedUserId != null) {
            if (!context.linkedUserExists) {
                throw EntityNotFoundException("User account '${context.linkedUserId}' not found in this studio")
            }
            if (context.linkedUserAlreadyAssigned) {
                throw ConflictException("User account '${context.linkedUserId}' is already linked to another active employee")
            }
        }
    }
}
