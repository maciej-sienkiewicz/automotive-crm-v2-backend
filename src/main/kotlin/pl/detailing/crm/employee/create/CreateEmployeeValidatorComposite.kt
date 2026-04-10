package pl.detailing.crm.employee.create

import org.springframework.stereotype.Component
import pl.detailing.crm.employee.create.validators.EmployeeEmailUniquenessValidator
import pl.detailing.crm.employee.create.validators.UserAccountExistenceValidator

@Component
class CreateEmployeeValidatorComposite(
    private val contextBuilder: CreateEmployeeValidationContextBuilder,
    private val emailUniquenessValidator: EmployeeEmailUniquenessValidator,
    private val userAccountExistenceValidator: UserAccountExistenceValidator
) {
    suspend fun validate(command: CreateEmployeeCommand) {
        val context = contextBuilder.build(command)
        emailUniquenessValidator.validate(context)
        userAccountExistenceValidator.validate(context)
    }
}
