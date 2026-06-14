package pl.detailing.crm.auth.passwordreset

data class ForgotPasswordRequest(
    val email: String
)

data class ResetPasswordRequest(
    val token: String,
    val password: String,
    val confirmPassword: String
)

data class ValidateResetTokenResponse(
    val valid: Boolean
)
