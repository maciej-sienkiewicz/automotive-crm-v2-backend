package pl.detailing.crm.gus.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gus")
data class GusProperties(
    /** Klucz API do usługi GUS BIR. Wymagany – ustaw zmienną env GUS_API_KEY. */
    val apiKey: String,

    /** URL endpointu SOAP. Domyślnie środowisko produkcyjne. */
    val endpointUrl: String = "https://wyszukiwarkaregon.stat.gov.pl/wsBIR/UslugaBIRzewnPubl.svc",

    /** Czas oczekiwania na nawiązanie połączenia [ms]. */
    val connectTimeoutMs: Int = 5_000,

    /** Czas oczekiwania na odpowiedź [ms]. */
    val readTimeoutMs: Int = 15_000,

    /** Po ilu minutach sesja GUS jest odświeżana (serwer unieważnia po 60 min). */
    val sessionTtlMinutes: Long = 55,

    /** TTL wpisów w cache (Redis). Dane firm rzadko się zmieniają – domyślnie 24 h. */
    val cacheTtlHours: Long = 24,

    /** Maksymalna liczba prób przy błędach sieciowych. */
    val retryMaxAttempts: Int = 3,

    /** Opóźnienie przed pierwszą ponowną próbą [ms]. Rośnie wykładniczo. */
    val retryInitialDelayMs: Long = 1_000,

    /** Próg procentowy błędów otwierający Circuit Breaker. */
    val circuitBreakerFailureRateThreshold: Float = 50f,

    /** Czas, przez który CB pozostaje w stanie OPEN przed przejściem do HALF-OPEN [s]. */
    val circuitBreakerWaitDurationSeconds: Long = 60,

    /** Rozmiar okna przesuwnego dla CB (liczba wywołań). */
    val circuitBreakerSlidingWindowSize: Int = 10
)
