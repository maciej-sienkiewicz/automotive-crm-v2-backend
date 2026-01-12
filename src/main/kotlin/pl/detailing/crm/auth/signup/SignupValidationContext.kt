package pl.detailing.crm.auth.signup

data class SignupValidationContext(
    val firstName: String,
    val lastName: String,
    val email: String,
    val password: String,
    val confirmPassword: String,
    val acceptTerms: Boolean,
    val emailExists: Boolean
)