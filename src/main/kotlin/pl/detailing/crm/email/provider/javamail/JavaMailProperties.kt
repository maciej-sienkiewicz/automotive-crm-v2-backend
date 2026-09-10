package pl.detailing.crm.email.provider.javamail

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * JavaMail SMTP configuration.
 *
 * Set [enabled] = false in non-production environments to log messages without
 * actually dispatching them via SMTP.
 */
@ConfigurationProperties(prefix = "email.javamail")
data class JavaMailProperties(
    /** SMTP server hostname. */
    val host: String = "localhost",
    /** SMTP port (587 = STARTTLS, 465 = SSL/TLS). */
    val port: Int = 587,
    /** SMTP authentication username. */
    val username: String = "",
    /** SMTP authentication password. */
    val password: String = "",
    /** Whether SMTP AUTH is required. */
    val smtpAuth: Boolean = true,
    /** Whether to use STARTTLS for connection encryption. */
    val smtpStarttlsEnable: Boolean = true,
    /** When false the provider only logs; does NOT call the SMTP server. */
    val enabled: Boolean = false,
    /**
     * Sender address (`From`). Blank = the SMTP [username]: providers such as OVH and
     * home.pl bounce a message whose From does not match the authenticated account, and
     * they do it AFTER accepting the message, so the bounce is the only trace.
     */
    val from: String = "",
    /** Display name shown next to the sender address. */
    val fromName: String = "DetailBoost",
    /** Optional `Reply-To` when replies should land in a different mailbox than [from]. */
    val replyTo: String = ""
)
