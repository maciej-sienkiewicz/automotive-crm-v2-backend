package pl.detailing.crm.communication.whitelist

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import pl.detailing.crm.shared.normalizeToE164

/**
 * Answers one question for the outbound gateway: may this customer message go to this
 * recipient while the whitelist is in force?
 *
 * Normalization is deliberately the same as the one used for matching customers
 * ([normalizeToE164]): "+48 500-100-200", "500100200" and "0048500100200" are one number.
 * A number that cannot be normalized is never allowed — a list entry that matches garbage
 * would match the wrong people.
 */
@Configuration
@EnableConfigurationProperties(RecipientWhitelistProperties::class)
class RecipientWhitelist(private val properties: RecipientWhitelistProperties) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val phones: Set<String> = properties.phones.mapNotNull { normalizeToE164(it) }.toSet()
    private val emails: Set<String> = properties.emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    val enabled: Boolean get() = properties.enabled

    fun allowsPhone(phone: String): Boolean {
        if (!properties.enabled) return true
        val normalized = normalizeToE164(phone) ?: return false
        return normalized in phones
    }

    fun allowsEmail(email: String): Boolean {
        if (!properties.enabled) return true
        return email.trim().lowercase() in emails
    }

    @PostConstruct
    fun announce() {
        when {
            !properties.enabled ->
                logger.warn("Recipient whitelist DISABLED — customer messages may reach anyone (go-live mode)")
            phones.isEmpty() && emails.isEmpty() ->
                logger.warn("Recipient whitelist enabled with NO entries — every customer SMS and e-mail will be blocked unless the studio redirects to itself")
            else ->
                logger.warn("Recipient whitelist enabled: {} phone(s), {} e-mail(s) may receive customer messages", phones.size, emails.size)
        }
        properties.phones.filter { normalizeToE164(it) == null }.forEach {
            logger.error("Recipient whitelist entry '{}' is not a valid phone number and will never match", it)
        }
    }

    companion object {
        const val BLOCK_REASON_SMS = "Numer poza whitelistą odbiorców (faza testowa) — wiadomość zablokowana"
        const val BLOCK_REASON_EMAIL = "Adres poza whitelistą odbiorców (faza testowa) — wiadomość zablokowana"
    }
}
