package pl.detailing.crm.rolepreview

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Co system ZROBIŁBY na zewnątrz, gdyby piaskownica była prawdziwym studiem: wysłał SMS,
 * e-mail, powiadomienie, fakturę do KSeF...
 *
 * Bezpiecznik piaskownicy zatrzymuje każdą taką akcję i zapisuje ją tutaj, a panel podglądu
 * ją pokazuje. Dla biznesu to często najcenniejsza część podglądu: „pracownik z tą rolą
 * może wysłać klientowi SMS - o, tak by wyglądał".
 *
 * Wpisy żyją w Redisie, nie w bazie: to ślad chwilowego podglądu, znika razem z piaskownicą
 * (i najpóźniej po dobie sam z siebie).
 */
@Service
class RolePreviewEffects(
    private val redisTemplate: StringRedisTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    fun record(studioId: UUID, channel: SimulatedEffectChannel, recipient: String?, summary: String) {
        val effect = SimulatedEffect(
            channel = channel,
            recipient = recipient?.take(MAX_RECIPIENT_LENGTH),
            summary = summary.take(MAX_SUMMARY_LENGTH),
            atEpochMillis = Instant.now().toEpochMilli()
        )
        // Zapis śladu nie może zamienić zablokowanej wysyłki w błąd użytkownika - blokada
        // zadziałała, a to, czy panel ją pokaże, jest sprawą drugorzędną.
        runCatching {
            val key = key(studioId)
            redisTemplate.opsForList().leftPush(key, mapper.writeValueAsString(effect))
            redisTemplate.opsForList().trim(key, 0, MAX_EFFECTS - 1)
            redisTemplate.expire(key, TTL)
        }.onFailure { logger.warn("Nie udało się zapisać efektu piaskownicy studio={}: {}", studioId, it.message) }
    }

    /** Najnowsze najpierw. */
    fun list(studioId: UUID): List<SimulatedEffect> =
        runCatching {
            redisTemplate.opsForList().range(key(studioId), 0, MAX_EFFECTS - 1).orEmpty()
                .mapNotNull { json -> runCatching { mapper.readValue<SimulatedEffect>(json) }.getOrNull() }
        }.getOrElse {
            logger.warn("Nie udało się odczytać efektów piaskownicy studio={}: {}", studioId, it.message)
            emptyList()
        }

    fun clear(studioId: UUID) {
        redisTemplate.delete(key(studioId))
    }

    private fun key(studioId: UUID) = "role-preview:effects:$studioId"

    private companion object {
        const val MAX_EFFECTS = 100L
        const val MAX_RECIPIENT_LENGTH = 200
        const val MAX_SUMMARY_LENGTH = 500
        val TTL: Duration = Duration.ofDays(1)
    }
}

/** Kanał, którym system wyszedłby na zewnątrz. */
enum class SimulatedEffectChannel(val label: String) {
    SMS("SMS"),
    EMAIL("E-mail"),
    PUSH("Powiadomienie push"),
    KSEF("KSeF"),
    PAYMENT("Płatność"),
    AI("Asystent AI"),
    INSTAGRAM("Instagram"),
    MAILBOX("Skrzynka pocztowa"),
    COMPANY_REGISTRY("Rejestr firm (GUS)"),
    WEB_SEARCH("Wyszukiwanie w internecie"),
    /** Dane wspólne dla wszystkich studiów (np. katalog produktów) - poza piaskownicą, choć w naszej bazie. */
    SHARED_DATA("Dane wspólne wszystkich studiów"),
    OTHER("Usługa zewnętrzna")
}

data class SimulatedEffect(
    val channel: SimulatedEffectChannel,
    val recipient: String?,
    val summary: String,
    val atEpochMillis: Long
)
