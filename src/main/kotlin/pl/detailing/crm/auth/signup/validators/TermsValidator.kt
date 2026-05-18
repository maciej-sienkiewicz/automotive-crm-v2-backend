package pl.detailing.crm.auth.signup.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.signup.SignupValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class TermsValidator {
    fun validate(context: SignupValidationContext) {
        if (!context.acceptTerms) {
            throw ValidationException("Musisz zaakceptować regulamin i warunki korzystania")
        }
    }
}