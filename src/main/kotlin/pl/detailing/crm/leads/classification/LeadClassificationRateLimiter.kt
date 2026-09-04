package pl.detailing.crm.leads.classification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Dzienny sufit klasyfikacji per STUDIO.
 *
 * Ten automat różni się od generowania postów tym, że nikt go nie uruchamia świadomie —
 * odpala go przychodząca poczta. Liczba wywołań modelu zależy więc od tego, ile maili
 * ktoś studiu wyśle, a to nie jest wielkość, nad którą studio ani my mamy kontrolę.
 * Kampania spamowa albo doczytanie starego folderu po resecie UIDVALIDITY potrafią
 * wsypać tysiące wiadomości w kilka minut.
 *
 * Licznik jest ostatnim progiem PRZED wywołaniem modelu i zarazem jedynym, który
 * chroni budżet w scenariuszu, jakiego nie przewidzieliśmy. Po jego przekroczeniu
 * wiadomości nie giną: zostają w skrzynce i można je oznaczyć ręcznie, tak jak przed
 * wdrożeniem tej funkcji.
 *
 * Wzorzec i klucze jak w [pl.detailing.crm.instagram.ai.ratelimit.InstagramAiRateLimiter],
 * z jedną różnicą: tam przekroczenie limitu leci wyjątkiem do użytkownika, który
 * kliknął. Tutaj nie ma komu go pokazać, więc limiter zwraca `false`, a wywołujący
 * zapisuje pominięcie w dzienniku.
 */
@Component
class LeadClassificationRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${crm.ai.lead-classification.rate-limit.per-day:500}") private val perDayLimit: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @return true, gdy wolno wykonać klasyfikację (i licznik został podbity).
     */
    fun tryConsume(studioId: UUID): Boolean {
        // 0 albo mniej = wyłączony limit. Bez tego nie dałoby się prowadzić studia
        // pilotażowego z dużą skrzynką bez przebudowy konfiguracji.
        if (perDayLimit <= 0) return true

        val key = "$KEY_PREFIX:$studioId:d"
        val count = try {
            val incremented = redisTemplate.opsForValue().increment(key) ?: 1L
            if (incremented == 1L) redisTemplate.expire(key, Duration.ofSeconds(DAY_WINDOW_SECONDS))
            incremented
        } catch (e: Exception) {
            // Redis nieosiągalny nie może zatrzymać przetwarzania poczty. Przepuszczamy,
            // bo tańszy jest dzień bez limitu niż dzień bez leadów — a kill-switch
            // w properties zostaje na wypadek, gdyby to jednak był zły dzień.
            log.warn("[LEAD_CLASSIFY] Licznik limitu niedostępny ({}) — przepuszczam", e.message)
            return true
        }

        if (count > perDayLimit) {
            // Jedna linia na przekroczenie, nie na każdą kolejną wiadomość: przy zalanej
            // skrzynce ten log sam stałby się problemem.
            if (count == perDayLimit + 1L) {
                log.warn(
                    "[LEAD_CLASSIFY] Studio {} wyczerpało dzienny limit klasyfikacji ({}). " +
                        "Kolejne wiadomości pomijam do końca doby.",
                    studioId, perDayLimit
                )
            }
            return false
        }
        return true
    }

    companion object {
        private const val KEY_PREFIX = "ratelimit:lead-classify"
        private const val DAY_WINDOW_SECONDS = 86_400L
    }
}
