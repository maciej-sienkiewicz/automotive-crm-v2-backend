package pl.detailing.crm.employee.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.employee.infrastructure.EmployeeRepository

@Component
class CreateEmployeeValidationContextBuilder(
    private val employeeRepository: EmployeeRepository
) {
    suspend fun build(command: CreateEmployeeCommand): CreateEmployeeValidationContext =
        withContext(Dispatchers.IO) {
            val emailAlreadyExists = command.email?.let {
                employeeRepository.existsByStudioIdAndEmail(command.studioId.value, it.lowercase())
            } ?: false

            CreateEmployeeValidationContext(
                studioId = command.studioId,
                firstName = command.firstName,
                lastName = command.lastName,
                email = command.email,
                emailAlreadyExists = emailAlreadyExists
            )
        }
}
