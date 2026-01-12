package pl.detailing.crm.auth.signup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.user.infrastructure.UserRepository

@Component
class SignupValidationContextBuilder(
    private val userRepository: UserRepository
) {
    suspend fun build(request: SignupRequest): SignupValidationContext = withContext(Dispatchers.IO) {
        val emailExistsDeferred = async {
            userRepository.findByEmail(request.email) != null
        }

        SignupValidationContext(
            firstName = request.firstName,
            lastName = request.lastName,
            email = request.email,
            password = request.password,
            confirmPassword = request.confirmPassword,
            acceptTerms = request.acceptTerms,
            emailExists = emailExistsDeferred.await()
        )
    }
}