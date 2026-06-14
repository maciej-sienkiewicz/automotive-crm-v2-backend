package pl.detailing.crm.auth

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException

/**
 * Central password strength policy, shared by signup and password reset so the
 * rules (and their Polish error messages) stay consistent in a single place.
 */
@Component
class PasswordPolicy {

    fun validate(password: String, confirmPassword: String) {
        if (password != confirmPassword) {
            throw ValidationException("Hasła nie są zgodne")
        }

        if (password.length < 8) {
            throw ValidationException("Hasło musi mieć co najmniej 8 znaków")
        }

        if (!password.any { it.isUpperCase() }) {
            throw ValidationException("Hasło musi zawierać co najmniej jedną wielką literę")
        }

        if (!password.any { it.isLowerCase() }) {
            throw ValidationException("Hasło musi zawierać co najmniej jedną małą literę")
        }

        if (!password.any { it.isDigit() }) {
            throw ValidationException("Hasło musi zawierać co najmniej jedną cyfrę")
        }
    }
}
