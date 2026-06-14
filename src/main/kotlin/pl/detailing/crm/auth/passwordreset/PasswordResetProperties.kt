package pl.detailing.crm.auth.passwordreset

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the self-service password reset flow.
 */
@ConfigurationProperties(prefix = "auth.password-reset")
data class PasswordResetProperties(
    /** How long a reset link stays valid, in minutes. */
    val tokenTtlMinutes: Long = 30,
    /**
     * Minimum interval between two reset emails for the same address, in seconds.
     * Prevents inbox flooding while keeping the public response identical.
     */
    val requestCooldownSeconds: Long = 60,
    /** Frontend origin used to build the reset link sent in the email. */
    val frontendBaseUrl: String = "https://detailboost.pl"
)
