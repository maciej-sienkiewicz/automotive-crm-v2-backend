package pl.detailing.crm.auth.signup.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.signup.SignupValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class PasswordValidator {
    fun validate(context: SignupValidationContext) {
        val password = context.password

        if (password != context.confirmPassword) {
            throw ValidationException("Passwords do not match")
        }

        if (password.length < 8) {
            throw ValidationException("Password must be at least 8 characters long")
        }

        if (!password.any { it.isUpperCase() }) {
            throw ValidationException("Password must contain at least one uppercase letter")
        }

        if (!password.any { it.isLowerCase() }) {
            throw ValidationException("Password must contain at least one lowercase letter")
        }

        if (!password.any { it.isDigit() }) {
            throw ValidationException("Password must contain at least one digit")
        }
    }
}
