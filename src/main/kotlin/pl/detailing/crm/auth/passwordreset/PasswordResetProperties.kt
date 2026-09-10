package pl.detailing.crm.auth.passwordreset

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the self-service password reset flow and for the employee
 * invitation link, which rides on the same one-time token mechanism.
 */
@ConfigurationProperties(prefix = "auth.password-reset")
data class PasswordResetProperties(
    /** How long a reset link stays valid, in minutes. */
    val tokenTtlMinutes: Long = 30,
    /**
     * How long an employee invitation (account setup) link stays valid, in hours.
     * Deliberately much longer than a reset link: the employee did not ask for the
     * e-mail and may open it days later, and every expiry costs the administrator
     * a manual re-send.
     */
    val invitationTokenTtlHours: Long = 48,
    /**
     * Minimum interval between two reset emails for the same address, in seconds.
     * Prevents inbox flooding while keeping the public response identical.
     */
    val requestCooldownSeconds: Long = 60,
    /** Frontend origin used to build the reset link sent in the email. */
    val frontendBaseUrl: String = "https://detailboost.pl"
)
