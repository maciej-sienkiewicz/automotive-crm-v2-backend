package pl.detailing.crm.auth.signup

data class SignupRequest(
    val firstName: String,
    val lastName: String,
    val email: String,
    val password: String,
    val confirmPassword: String,
    val acceptTerms: Boolean
)