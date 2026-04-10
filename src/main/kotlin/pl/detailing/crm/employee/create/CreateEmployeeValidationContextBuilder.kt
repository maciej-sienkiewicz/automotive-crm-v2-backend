package pl.detailing.crm.employee.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.user.infrastructure.UserRepository

@Component
class CreateEmployeeValidationContextBuilder(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository
) {
    suspend fun build(command: CreateEmployeeCommand): CreateEmployeeValidationContext =
        withContext(Dispatchers.IO) {
            val emailExistsDeferred = async {
                command.email?.let {
                    employeeRepository.existsActiveByStudioIdAndEmail(command.studioId.value, it)
                } ?: false
            }

            val linkedUserExistsDeferred = async {
                command.linkedUserId?.let {
                    userRepository.findByIdAndStudioId(it.value, command.studioId.value) != null
                } ?: true
            }

            val linkedUserAlreadyAssignedDeferred = async {
                command.linkedUserId?.let {
                    employeeRepository.existsActiveByStudioIdAndUserId(command.studioId.value, it.value)
                } ?: false
            }

            CreateEmployeeValidationContext(
                studioId = command.studioId,
                firstName = command.firstName,
                lastName = command.lastName,
                email = command.email,
                linkedUserId = command.linkedUserId,
                emailAlreadyExists = emailExistsDeferred.await(),
                linkedUserExists = linkedUserExistsDeferred.await(),
                linkedUserAlreadyAssigned = linkedUserAlreadyAssignedDeferred.await()
            )
        }
}
