package pl.detailing.crm.instagram.ads.discovery.ig

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Odpowiedź sidecara sprowadzona do tego, co nas obchodzi. */
data class IgLookupResult(
    val status: IgLookupStatus,
    val igUsername: String?,
    val reason: String? = null
)

/**
 * Klient sidecara `meta-ig-resolver`.
 *
 * Sidecar stoi osobno, bo niesie Chromium. Backend obsługuje faktury, wizyty
 * i płatności — nie chcemy w jego obrazie przeglądarki ani jej klasy awarii
 * (zużycie pamięci, osierocone procesy, `/dev/shm`). Granicą jest jedno
 * wywołanie HTTP po sieci wewnętrznej Dockera.
 *
 * Domyślnie WYŁĄCZONY. Włącza się świadomie zmienną środowiskową — nie chcemy,
 * żeby ktoś odpalił to przypadkiem na instalacji bez sidecara.
 */
@Component
class MetaIgResolverClient(
    private val objectMapper: ObjectMapper,
    @Value("\${meta.ads.ig-resolver.enabled:false}") private val enabledFlag: Boolean,
    @Value("\${meta.ads.ig-resolver.url:http://meta-ig-resolver:8080}") private val baseUrl: String,
    @Value("\${meta.ads.ig-resolver.timeout-seconds:60}") private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(MetaIgResolverClient::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // Sidecar stoi pod stałą nazwą w sieci Dockera i nie przekierowuje.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    val enabled: Boolean get() = enabledFlag && baseUrl.isNotBlank()

    /**
     * Pyta sidecar o nazwę profilu. Nigdy nie rzuca — awaria odczytu nazwy
     * Instagrama nie ma prawa wywrócić niczego, co ją wywołało.
     */
    fun resolve(pageId: String): IgLookupResult {
        if (!enabled) return IgLookupResult(IgLookupStatus.ERROR, null, "resolver wyłączony")

        val body = objectMapper.writeValueAsString(mapOf("pageId" to pageId))
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/resolve"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return@runCatching IgLookupResult(
                    IgLookupStatus.ERROR, null, "sidecar HTTP ${response.statusCode()}"
                )
            }
            parse(response.body())
        }.getOrElse { error ->
            log.warn("Odczyt profilu IG dla strony {} nie powiódł się: {}", pageId, error.message)
            val timedOut = error is java.net.http.HttpTimeoutException
            IgLookupResult(
                if (timedOut) IgLookupStatus.TIMEOUT else IgLookupStatus.ERROR,
                null,
                error.message?.take(200)
            )
        }
    }

    private fun parse(raw: String): IgLookupResult {
        val node = objectMapper.readTree(raw)
        val status = node.path("status").asText("").uppercase()
            .let { name -> IgLookupStatus.entries.firstOrNull { it.name == name } }
            ?: IgLookupStatus.ERROR

        val handle = node.path("igUsername").takeIf { !it.isNull }?.asText()
            ?.trim()?.trim('@')?.takeIf { it.isNotBlank() }

        // Sidecar obiecuje, że przy OK jest nazwa. Gdyby kiedyś skłamał,
        // wolimy zapisać EMPTY niż wiersz „udany" bez wartości.
        if (status == IgLookupStatus.OK && handle == null) {
            return IgLookupResult(IgLookupStatus.EMPTY, null, "OK bez nazwy profilu")
        }

        return IgLookupResult(status, handle, node.path("reason").takeIf { !it.isNull }?.asText())
    }
}
