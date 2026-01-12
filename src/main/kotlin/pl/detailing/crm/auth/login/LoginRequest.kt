package pl.detailing.crm.auth.login

data class LoginRequest(
    val email: String,
    val password: String,
    val rememberMe: Boolean = false
)