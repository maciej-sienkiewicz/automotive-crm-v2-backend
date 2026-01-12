package pl.detailing.crm.auth.signup.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.signup.SignupValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class NameValidator {
    fun validate(context: SignupValidationContext) {
        if (context.firstName.trim().length < 2) {
            throw ValidationException("First name must be at least 2 characters long")
        }

        if (context.lastName.trim().length < 2) {
            throw ValidationException("Last name must be at least 2 characters long")
        }

        if (context.firstName.trim().length > 50) {
            throw ValidationException("First name cannot exceed 50 characters")
        }

        if (context.lastName.trim().length > 50) {
            throw ValidationException("Last name cannot exceed 50 characters")
        }

        val nameRegex = "^[a-zA-ZÄ…Ä‡Ä™Å‚Å„Ć³Å›ĹşĹĽÄ„Ä†Ä˜Ĺ�ĹƒĆłĹšĹšĹ˝\\s-]+$".toRegex()
        if (!nameRegex.matches(context.firstName)) {
            throw ValidationException("First name contains invalid characters")
        }

        if (!nameRegex.matches(context.lastName)) {
            throw ValidationException("Last name contains invalid characters")
        }
    }
}