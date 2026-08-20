package pl.detailing.crm.ksef.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.akmf.ksef.sdk.api.DefaultKsefClient
import pl.akmf.ksef.sdk.api.KsefApiProperties
import pl.akmf.ksef.sdk.api.services.DefaultCryptographyService
import pl.akmf.ksef.sdk.client.interfaces.CryptographyService
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.detailing.crm.ksef.metrics.KsefApiMetrics
import pl.detailing.crm.ksef.metrics.MeteredKsefClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(KsefProperties::class)
class KsefClientConfig(private val properties: KsefProperties) {

    /**
     * Configures the official KSeF Java SDK client pointing at the configured API.
     * Uses JDK's built-in HttpClient (HTTP/1.1) so that system proxy settings
     * from JAVA_TOOL_OPTIONS are respected automatically.
     *
     * Wystawiany bean jest opakowany w licznik żądań: limity KSeF są naliczane per
     * kontekst NIP, a bez pomiaru per najemca dowiadywalibyśmy się o zbliżaniu do
     * nich dopiero z serii 429 w logach. Opakowanie jest w tym miejscu, a nie w
     * wywołaniach, bo tędy przechodzi każde żądanie do KSeF — także te dodane
     * w przyszłości.
     */
    @Bean
    fun ksefClient(ksefApiMetrics: KsefApiMetrics): KSeFClient {
        val objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build()

        val apiProperties = object : KsefApiProperties() {
            override fun getBaseUri(): String = properties.baseUrl
            override fun getSuffixUri(): String = properties.apiPath
            override fun getQrUri(): String = ""
            override fun getRequestTimeout(): Duration = Duration.ofSeconds(properties.requestTimeoutSeconds)
            override fun getDefaultHeaders(): Map<String, String> = emptyMap()
        }

        return MeteredKsefClient.wrap(
            DefaultKsefClient(httpClient, apiProperties, objectMapper),
            ksefApiMetrics
        )
    }

    /**
     * Provides RSA/ECIes encryption for KSeF token authentication.
     * On startup the service fetches the KSeF public key; if the API is
     * unreachable it falls back to "offline mode" and re-initializes on
     * first use.
     */
    @Bean
    fun ksefCryptographyService(ksefClient: KSeFClient): CryptographyService {
        return DefaultCryptographyService(ksefClient)
    }
}
