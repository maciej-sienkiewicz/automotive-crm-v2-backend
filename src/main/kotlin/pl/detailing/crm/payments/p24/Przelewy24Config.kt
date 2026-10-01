package pl.detailing.crm.payments.p24

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Konfiguracja Przelewy24 i jej głośna diagnostyka przy starcie.
 *
 * Brak poświadczeń NIE zatrzymuje aplikacji — CRM musi działać także wtedy, gdy płatności
 * leżą — ale też niczego nie rozdaje: checkout odpowiada 503 ([pl.detailing.crm.shared.PaymentsUnavailableException]),
 * a miernik `payments.p24.enabled` = 0 pozwala podpiąć alarm. Tryb mock jest wyłącznie jawny.
 */
@Configuration
@EnableConfigurationProperties(Przelewy24Properties::class)
class Przelewy24Config(
    private val properties: Przelewy24Properties,
    meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        Gauge.builder("payments.p24.enabled") { if (properties.isConfigured && !properties.mockMode) 1.0 else 0.0 }
            .description("1 = prawdziwa bramka Przelewy24 skonfigurowana; 0 = mock albo płatności wyłączone")
            .register(meterRegistry)
    }

    @PostConstruct
    fun reportPaymentMode() {
        when {
            properties.mockMode -> logger.warn(
                "Przelewy24: TRYB MOCK (p24.mock-mode=true) — zamówienia są realizowane BEZ pobrania pieniędzy. " +
                "Dopuszczalne wyłącznie lokalnie i w testach."
            )
            !properties.isConfigured -> logger.error(
                "Przelewy24: brak poświadczeń (P24_MERCHANT_ID/P24_POS_ID/P24_CRC/P24_API_KEY) — " +
                "płatności WYŁĄCZONE, checkout odpowiada 503. Ustaw poświadczenia albo jawnie P24_MOCK_MODE=true."
            )
            properties.sandbox -> logger.warn(
                "Przelewy24: środowisko SANDBOX ({}) — płatności testowe, nie wolno go używać produkcyjnie.",
                properties.apiBaseUrl
            )
            else -> logger.info("Przelewy24: środowisko produkcyjne ({}), posId={}", properties.apiBaseUrl, properties.posId)
        }
    }
}
