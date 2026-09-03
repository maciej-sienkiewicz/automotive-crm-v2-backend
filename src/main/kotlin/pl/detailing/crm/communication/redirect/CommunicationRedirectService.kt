package pl.detailing.crm.communication.redirect

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.normalizeToE164
import java.time.Instant
import java.util.UUID

/**
 * Single source of truth for "does this studio want its customer messages sent to itself".
 *
 * Why a studio setting and not an environment flag: the redirect is a business need, not a
 * developer one. A studio that has just written its templates wants to watch, on its own
 * phone and inbox, what a real customer would get for real bookings — for a day, a week,
 * as long as it takes — and then flip the switch off. The switch therefore lives next to
 * the templates, is visible to whoever manages them, and is scoped to that one studio.
 *
 * Fail-closed rules:
 *  - the redirect is active only when [CommunicationRedirectEntity.enabled] is true AND both
 *    targets are present; a half-filled row never redirects and never blocks;
 *  - enabling requires both a valid Polish/E.164 phone and a plausible e-mail address —
 *    "enabled but nowhere to send" is rejected at save time, not discovered at send time.
 */
@Service
class CommunicationRedirectService(
    private val repository: CommunicationRedirectJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun settings(studioId: StudioId): CommunicationRedirectSettings =
        repository.findByStudioId(studioId.value)?.toSettings() ?: CommunicationRedirectSettings.OFF

    /** Null means "send to the customer as usual". */
    fun activeFor(studioId: UUID): ActiveRedirect? {
        val row = repository.findByStudioId(studioId) ?: return null
        if (!row.enabled || row.phone.isBlank() || row.email.isBlank()) return null
        return ActiveRedirect(phone = row.phone, email = row.email)
    }

    @Transactional
    fun update(
        studioId: StudioId,
        enabled: Boolean,
        phone: String,
        email: String,
        updatedBy: UUID?
    ): CommunicationRedirectSettings {
        val normalizedPhone = normalizePhone(phone, required = enabled)
        val normalizedEmail = normalizeEmail(email, required = enabled)

        val row = repository.findByStudioId(studioId.value) ?: CommunicationRedirectEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            enabled = false,
            phone = "",
            email = "",
            updatedAt = Instant.now(),
            updatedByUserId = null
        )
        row.enabled = enabled
        row.phone = normalizedPhone
        row.email = normalizedEmail
        row.updatedAt = Instant.now()
        row.updatedByUserId = updatedBy
        repository.save(row)

        if (enabled) {
            logger.warn(
                "Communication redirect ENABLED for studio={} — every customer SMS goes to {} and every e-mail to {}",
                studioId.value, normalizedPhone, normalizedEmail
            )
        } else {
            logger.info("Communication redirect disabled for studio={} — messages go to customers", studioId.value)
        }
        return row.toSettings()
    }

    private fun normalizePhone(raw: String, required: Boolean): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            if (required) throw ValidationException("Podaj numer telefonu, na który mają trafiać SMS-y")
            return ""
        }
        return normalizeToE164(trimmed)
            ?: throw ValidationException("Numer telefonu „$trimmed” nie wygląda na prawidłowy. Użyj formatu +48 500 100 200")
    }

    private fun normalizeEmail(raw: String, required: Boolean): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            if (required) throw ValidationException("Podaj adres e-mail, na który mają trafiać wiadomości")
            return ""
        }
        if (!EMAIL.matches(trimmed)) {
            throw ValidationException("Adres e-mail „$trimmed” nie wygląda na prawidłowy")
        }
        return trimmed.lowercase()
    }

    private fun CommunicationRedirectEntity.toSettings() = CommunicationRedirectSettings(
        enabled = enabled && phone.isNotBlank() && email.isNotBlank(),
        phone = phone,
        email = email,
        updatedAt = updatedAt
    )

    private companion object {
        /** Deliberately lenient: one @, no whitespace, a dot in the domain. The SMTP server has the final say. */
        val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    }
}
