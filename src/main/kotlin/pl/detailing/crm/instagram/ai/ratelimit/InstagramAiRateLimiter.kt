package pl.detailing.crm.instagram.ai.ratelimit

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import java.time.Duration

/**
 * Limit generowań postów AI per STUDIO (nie per IP).
 *
 * [pl.detailing.crm.security.RateLimitFilter] chroni aplikację przed anonimowym ruchem
 * i liczy żądania per adres IP — dla tego endpointu to za mało: zalogowany użytkownik
 * jednego studia mógł w kilka minut wygenerować tysiące postów i wyczerpać budżet OpenAI,
 * bo mieścił się w ogólnym limicie API. Stąd osobny licznik na poziomie warstwy
 * aplikacyjnej, kluczowany identyfikatorem studia.
 *
 * Dwa niezależne okna (Redis INCR + EXPIRE przy pierwszym żądaniu w oknie):
 *   - `ratelimit:ig-ai:{studioId}:h` — 3600 s
 *   - `ratelimit:ig-ai:{studioId}:d` — 86400 s
 *
 * BUDŻET OPENAI: licznik zlicza ŻĄDANIA /generate, nie wywołania LLM. Jedno żądanie
 * uruchamia pętlę generuj → weryfikuj → popraw, czyli do 7 wywołań modelu
 * (1 generator + do 3 weryfikacji + do 3 korekt). Realny sufit dzienny na studio
 * to więc `per-day × 7` wywołań OpenAI — tak należy szacować koszt.
 */
@Component
class InstagramAiRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${instagram.ai.rate-limit.per-hour:10}") private val perHourLimit: Int,
    @Value("\${instagram.ai.rate-limit.per-day:40}") private val perDayLimit: Int
) {
    private val logger = LoggerFactory.getLogger(InstagramAiRateLimiter::class.java)

    companion object {
        private const val KEY_PREFIX = "ratelimit:ig-ai"
        private const val HOUR_WINDOW_SECONDS = 3600L
        private const val DAY_WINDOW_SECONDS = 86400L
        const val METRIC_RATE_LIMITED = "crm.instagram.ai.rate_limited"
    }

    private data class Window(val suffix: String, val tag: String, val limit: Int, val seconds: Long)

    /**
     * Zlicza żądanie i przepuszcza je dalej albo rzuca [InstagramAiRateLimitExceededException].
     *
     * Wywoływane na POCZĄTKU /generate — przed retrievalem z pgvector i przed jakimkolwiek
     * wywołaniem LLM, żeby odrzucone żądanie nie kosztowało ani jednego tokenu.
     */
    fun checkAndConsume(studioId: StudioId): InstagramAiRateLimitStatus {
        val windows = listOf(
            Window("h", "hour", perHourLimit, HOUR_WINDOW_SECONDS),
            Window("d", "day", perDayLimit, DAY_WINDOW_SECONDS)
        )

        var tightest: InstagramAiRateLimitStatus? = null

        for (window in windows) {
            val key = "$KEY_PREFIX:${studioId.value}:${window.suffix}"
            val count = redisTemplate.opsForValue().increment(key) ?: 1L
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(window.seconds))
            }

            val remaining = maxOf(0L, window.limit - count)
            val status = InstagramAiRateLimitStatus(window.tag, window.limit, remaining)

            if (count > window.limit) {
                meterRegistry.counter(METRIC_RATE_LIMITED, "window", window.tag).increment()
                val retryAfter = redisTemplate.getExpire(key)?.takeIf { it > 0 } ?: window.seconds
                logger.warn(
                    "Instagram AI rate limit exceeded: studioId={}, window={}, count={}, limit={}",
                    studioId, window.tag, count, window.limit
                )
                throw InstagramAiRateLimitExceededException(
                    limit = window.limit,
                    window = window.tag,
                    retryAfterSeconds = retryAfter,
                    message = "Przekroczono limit generowania postów AI " +
                        "(${window.limit} ${describeWindow(window.tag)}). " +
                        "Limit odnowi się za ${describeDelay(retryAfter)}."
                )
            }

            // Nagłówki X-RateLimit-* opisują okno, które jest najbliżej wyczerpania.
            if (tightest == null || remaining < tightest.remaining) tightest = status
        }

        return tightest!!
    }

    private fun describeWindow(tag: String): String = if (tag == "hour") "na godzinę" else "na dobę"

    private fun describeDelay(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}

/**
 * Stan licznika dla okna najbliższego wyczerpaniu — trafia do nagłówków
 * X-RateLimit-Limit / X-RateLimit-Remaining, tak jak w [pl.detailing.crm.security.RateLimitFilter].
 */
data class InstagramAiRateLimitStatus(
    val window: String,
    val limit: Int,
    val remaining: Long
)

class InstagramAiRateLimitExceededException(
    val limit: Int,
    val window: String,
    val retryAfterSeconds: Long,
    message: String
) : RuntimeException(message)
