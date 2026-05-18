package pl.detailing.crm.auth.signup.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.signup.SignupValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class NameValidator {
    fun validate(context: SignupValidationContext) {
        if (context.firstName.trim().length < 2) {
            throw ValidationException("Imię musi mieć co najmniej 2 znaki")
        }

        if (context.lastName.trim().length < 2) {
            throw ValidationException("Nazwisko musi mieć co najmniej 2 znaki")
        }

        if (context.firstName.trim().length > 50) {
            throw ValidationException("Imię nie może przekraczać 50 znaków")
        }

        if (context.lastName.trim().length > 50) {
            throw ValidationException("Nazwisko nie może przekraczać 50 znaków")
        }

        val nameRegex = "^[a-zA-ZÄ…Ä‡Ä™Å‚Å„Ć³Å›ĹşĹĽÄ„Ä†Ä˜Ĺ�ĹƒĆłĹšĹšĹ˝\\s-]+$".toRegex()
        if (!nameRegex.matches(context.firstName)) {
            throw ValidationException("Imię zawiera niedozwolone znaki")
        }

        if (!nameRegex.matches(context.lastName)) {
            throw ValidationException("Nazwisko zawiera niedozwolone znaki")
        }
    }
}