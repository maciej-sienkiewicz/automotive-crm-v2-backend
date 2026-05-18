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
                throw EntityNotFoundException("Konto użytkownika '${context.linkedUserId}' nie zostało znalezione w tym studiu")
            }
            if (context.linkedUserAlreadyAssigned) {
                throw ConflictException("Konto użytkownika '${context.linkedUserId}' jest już powiązane z innym aktywnym pracownikiem")
            }
        }
    }
}
