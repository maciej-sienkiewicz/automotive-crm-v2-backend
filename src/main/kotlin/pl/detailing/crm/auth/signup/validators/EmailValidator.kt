package pl.detailing.crm.auth.signup.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.signup.SignupValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class EmailValidator {
    fun validate(context: SignupValidationContext) {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()

        if (!emailRegex.matches(context.email)) {
            throw ValidationException("Invalid email format")
        }

        if (context.emailExists) {
            throw ValidationException("Email already registered")
        }
    }
}