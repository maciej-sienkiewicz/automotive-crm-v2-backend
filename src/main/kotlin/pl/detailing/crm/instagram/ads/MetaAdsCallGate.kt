package pl.detailing.crm.instagram.ads

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Bramka wywołań Biblioteki reklam Meta.
 *
 * Meta liczy 200 wywołań na godzinę na token i nie zwraca po drodze żadnego
 * ostrzeżenia — po przekroczeniu limitu po prostu odmawia. Trzymamy więc własny
 * licznik z zapasem: przy kilkudziesięciu obserwowanych stronach i batchu po 10
 * stron na wywołanie dobowy sync mieści się w kilkunastu requestach, więc limit
 * jest zabezpieczeniem przed pętlą, nie codziennym ograniczeniem.
 */
@Component
class MetaAdsCallGate(
    private val meterRegistry: MeterRegistry,
    @Value("\${meta.ads.calls-per-hour:180}") callsPerHour: Int
) {
    private val log = LoggerFactory.getLogger(MetaAdsCallGate::class.java)

    private val rateLimiter: RateLimiter = RateLimiter.of(
        "meta-ads-archive",
        RateLimiterConfig.custom()
            .limitForPeriod(callsPerHour)
            .limitRefreshPeriod(Duration.ofHours(1))
            // Czekanie godzinę na slot byłoby zawieszeniem synchronizacji; wolimy
            // odmówić i dokończyć przy następnym przebiegu.
            .timeoutDuration(Duration.ZERO)
            .build()
    )

    fun <T> call(endpoint: String, block: () -> T): T {
        if (!rateLimiter.acquirePermission()) {
            meterRegistry.counter("meta.ads.rate_limited").increment()
            throw MetaAdsException(
                statusCode = null,
                errorCode = null,
                errorSubcode = null,
                message = "Limit wywołań Biblioteki reklam Meta wyczerpany — wywołanie $endpoint pominięte."
            )
        }
        meterRegistry.counter("meta.ads.calls", "endpoint", endpoint).increment()
        return block()
    }

    fun logStartup(enabled: Boolean) {
        if (enabled) log.info("Meta Ad Library: klient aktywny")
        else log.info("Meta Ad Library: brak tokena (meta.ads.token) — reklamy konkurencji wyłączone")
    }
}
