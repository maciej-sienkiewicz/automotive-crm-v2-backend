package pl.detailing.crm.auth.signup.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.PasswordPolicy
import pl.detailing.crm.auth.signup.SignupValidationContext

@Component
class PasswordValidator(
    private val passwordPolicy: PasswordPolicy
) {
    fun validate(context: SignupValidationContext) {
        passwordPolicy.validate(context.password, context.confirmPassword)
    }
}
