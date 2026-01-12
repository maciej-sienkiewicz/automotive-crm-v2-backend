package pl.detailing.crm.auth.signup

import org.springframework.stereotype.Component
import pl.detailing.crm.auth.signup.validators.*

@Component
class SignupValidatorComposite(
    private val contextBuilder: SignupValidationContextBuilder,
    private val nameValidator: NameValidator,
    private val emailValidator: EmailValidator,
    private val passwordValidator: PasswordValidator,
    private val termsValidator: TermsValidator
) {
    suspend fun validate(request: SignupRequest) {
        val context = contextBuilder.build(request)

        nameValidator.validate(context)
        emailValidator.validate(context)
        passwordValidator.validate(context)
        termsValidator.validate(context)
    }
}