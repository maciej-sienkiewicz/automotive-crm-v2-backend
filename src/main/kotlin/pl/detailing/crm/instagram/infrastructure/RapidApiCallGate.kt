package pl.detailing.crm.instagram.infrastructure

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bramka wszystkich wywołań RapidAPI – jedno miejsce kontroli kosztów:
 *
 * 1. Rate limiter (domyślnie 4 req/s) – trzymamy się poniżej limitów planu RapidAPI
 *    i nie zalewamy dostawcy przy pełnym syncu wielu profili.
 * 2. Dzienny budżet wywołań (domyślnie 2000) – twardy bezpiecznik przed niekontrolowanym
 *    zużyciem płatnej quoty (pętla, błąd paginacji, nagły wzrost liczby profili).
 *    Po wyczerpaniu budżetu wywołania rzucają [InstagramProviderException] i sync
 *    kończy się z logiem – reszta profili poczeka do następnego dnia.
 * 3. Licznik Micrometer `instagram.rapidapi.calls` (tag endpoint) – widoczność zużycia
 *    quoty w Prometheusie.
 */
@Component
class RapidApiCallGate(
    private val meterRegistry: MeterRegistry,
    @Value("\${instagram.rapidapi.rate-limit-per-second:4}") ratePerSecond: Int,
    @Value("\${instagram.rapidapi.daily-budget:2000}") private val dailyBudget: Int
) {
    private val log = LoggerFactory.getLogger(RapidApiCallGate::class.java)

    private val rateLimiter: RateLimiter = RateLimiter.of(
        "instagram-rapidapi",
        RateLimiterConfig.custom()
            .limitForPeriod(ratePerSecond)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofSeconds(60))
            .build()
    )

    private val budgetDay = AtomicReference(LocalDate.now(ZoneOffset.UTC))
    private val usedToday = AtomicInteger(0)

    fun <T> call(endpoint: String, username: String, block: () -> T): T {
        resetBudgetIfNewDay()

        val used = usedToday.incrementAndGet()
        if (used > dailyBudget) {
            usedToday.decrementAndGet()
            meterRegistry.counter("instagram.rapidapi.budget_exceeded").increment()
            throw InstagramProviderException(
                username = username,
                statusCode = null,
                message = "Dzienny budżet wywołań RapidAPI ($dailyBudget) wyczerpany – wywołanie $endpoint pominięte."
            )
        }

        if (used == dailyBudget * 8 / 10) {
            log.warn("RapidAPI: zużyto 80% dziennego budżetu wywołań ({}/{})", used, dailyBudget)
        }

        meterRegistry.counter("instagram.rapidapi.calls", "endpoint", endpoint).increment()
        return rateLimiter.executeSupplier { block() }
    }

    fun usedToday(): Int {
        resetBudgetIfNewDay()
        return usedToday.get()
    }

    private fun resetBudgetIfNewDay() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val previous = budgetDay.getAndSet(today)
        if (previous != today) {
            usedToday.set(0)
        }
    }
}
