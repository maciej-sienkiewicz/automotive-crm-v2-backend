package pl.detailing.crm.instagram.ads

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Domena reklamodawcy → nazwa jego profilu na Instagramie.
 *
 * Dwa źródła, w tej kolejności:
 *
 * 1. **Własna baza.** Przy każdym monitorowanym profilu trzymamy `external_url`
 *    z jego bio. Gdy reklamodawca kieruje na tę samą domenę, to jest ta sama
 *    firma — trafienie pewne, bez ani jednego zapytania na zewnątrz.
 * 2. **Strona firmy.** Dla reszty pobieramy stronę spod domeny z reklamy
 *    i czytamy z niej link do Instagrama. Jeden GET na domenę, wynik w pamięci.
 *
 * Przedstawiamy się własnym `User-Agent`: to jest pojedyncze pobranie strony
 * głównej firmy, która sama chce być znaleziona, a nie przeczesywanie serwisu.
 * Kto nas nie chce, odrzuci po nagłówku — i dobrze, bo wtedy po prostu nie ma
 * nazwy IG, zamiast udawania przeglądarki.
 *
 * Cały krok jest OZDOBNY. Każdy błąd, timeout i pusty wynik kończy się brakiem
 * nazwy IG przy jednym wierszu wyników — nigdy błędem wyszukiwania.
 */
@Component
class AdvertiserInstagramResolver(
    private val profileRepository: InstagramProfileRepository,
    @Value("\${meta.ads.instagram-lookup.enabled:true}") private val enabled: Boolean,
    @Value("\${meta.ads.instagram-lookup.timeout-seconds:3}") private val timeoutSeconds: Long,
    @Value("\${meta.ads.instagram-lookup.budget-seconds:5}") private val budgetSeconds: Long
) {
    private val log = LoggerFactory.getLogger(AdvertiserInstagramResolver::class.java)

    private companion object {
        /** Nazwa profilu firmy zmienia się raz na nigdy — trzymamy długo. */
        val HIT_TTL: Duration = Duration.ofDays(30)

        /** Brak też zapamiętujemy, ale krócej: strona mogła akurat nie odpowiedzieć. */
        val MISS_TTL: Duration = Duration.ofDays(3)

        const val USER_AGENT = "DetailBoostBot/1.0 (+https://detailboost.pl)"

        /** apex → www → https to realne dwa skoki; trzeci to już pętla albo zabawa. */
        const val MAX_REDIRECTS = 3

        /** Pamięć podręczna nie ma prawa rosnąć w nieskończoność przez rok pracy. */
        const val MAX_CACHE_ENTRIES = 5_000
    }

    private data class Cached(val handle: String?, val at: Instant) {
        fun fresh(now: Instant): Boolean =
            now.isBefore(at.plus(if (handle != null) HIT_TTL else MISS_TTL))
    }

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * Przekierowania obsługujemy RĘCZNIE, mimo że klient umie to sam.
     *
     * Domena bierze się z reklamy, czyli spoza naszej kontroli: reklamodawca wpisuje
     * dowolny adres, a jego serwer może odesłać nas dokąd zechce. Automatyczne
     * podążanie za przekierowaniem omijałoby kontrolę z [publiclyRoutable] przy
     * pierwszym skoku — a to jest dokładnie ta luka, przez którą cudzy serwis
     * każe nam zapukać do adresu w sieci wewnętrznej.
     */
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Nazwy profili dla podanych domen. Klucz wyniku to domena, wartość to nazwa
     * bez małpy. Domeny bez trafienia po prostu nie wchodzą do mapy.
     */
    fun resolve(rawDomains: Collection<String?>): Map<String, String> {
        if (!enabled) return emptyMap()

        val domains = rawDomains
            .mapNotNull(AdvertiserInstagram::hostOf)
            .filterNot(AdvertiserInstagram::isIntermediary)
            .distinct()
        if (domains.isEmpty()) return emptyMap()

        val now = Instant.now()
        val found = mutableMapOf<String, String>()
        val pending = mutableListOf<String>()

        domains.forEach { domain ->
            val cached = cache[domain]?.takeIf { it.fresh(now) }
            when {
                cached != null -> cached.handle?.let { found[domain] = it }
                else -> fromOwnProfiles(domain)
                    ?.also { remember(domain, it); found[domain] = it }
                    ?: pending.add(domain)
            }
        }

        if (pending.isNotEmpty()) found += fromWebsites(pending)
        return found
    }

    /**
     * Konkurent, którego już obserwujemy na Instagramie, ma w bio ten sam adres,
     * na który kieruje reklamę. To jedyne trafienie, które jest pewne — resztę
     * zgadujemy ze stopki cudzej strony.
     */
    private fun fromOwnProfiles(domain: String): String? =
        runCatching {
            profileRepository.findByExternalUrlLike(domain)
                .firstOrNull { AdvertiserInstagram.sameHost(domain, it.externalUrl) }
                ?.username
        }.getOrNull()

    /**
     * Wszystkie strony naraz, z twardym budżetem na całość. Kto nie zdąży,
     * nie ma nazwy IG — wyszukiwanie nie może czekać na najwolniejszy serwer
     * w stawce.
     */
    private fun fromWebsites(domains: List<String>): Map<String, String> {
        val futures = domains.associateWith { fetchHandle(it) }

        runCatching {
            CompletableFuture
                .allOf(*futures.values.toTypedArray())
                .get(budgetSeconds, TimeUnit.SECONDS)
        }.onFailure {
            log.debug("Instagram reklamodawcy: budżet {} s wyczerpany, część stron nie zdążyła", budgetSeconds)
        }

        return buildMap {
            futures.forEach { (domain, future) ->
                val handle = if (future.isDone && !future.isCompletedExceptionally) future.getNow(null) else null
                // Niedokończonego pobrania NIE zapamiętujemy jako braku: następnym
                // razem strona może odpowiedzieć szybciej.
                if (future.isDone) remember(domain, handle)
                handle?.let { put(domain, it) }
            }
            futures.values.forEach { it.cancel(true) }
        }
    }

    private fun fetchHandle(domain: String): CompletableFuture<String?> =
        follow(runCatching { URI.create("https://$domain/") }.getOrNull(), MAX_REDIRECTS)

    /**
     * Jeden skok: sprawdza adres, pobiera, a przy przekierowaniu wywołuje się
     * ponownie — z ponowną kontrolą hosta, bo o to w tym całym zachodzie chodzi.
     */
    private fun follow(uri: URI?, hopsLeft: Int): CompletableFuture<String?> {
        val host = uri?.host?.lowercase()
        if (uri == null || host.isNullOrBlank() || hopsLeft <= 0) return CompletableFuture.completedFuture(null)
        if (uri.scheme !in setOf("http", "https")) return CompletableFuture.completedFuture(null)
        if (AdvertiserInstagram.isIntermediary(host.removePrefix("www."))) return CompletableFuture.completedFuture(null)
        if (!publiclyRoutable(host)) {
            log.debug("Instagram reklamodawcy: {} pominięty — adres spoza otwartego internetu", host)
            return CompletableFuture.completedFuture(null)
        }

        val request = runCatching {
            HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "pl,en;q=0.8")
                .GET()
                .build()
        }.getOrNull() ?: return CompletableFuture.completedFuture(null)

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handle { response, error ->
                when {
                    error != null -> {
                        log.debug("Instagram reklamodawcy: {} nieosiągalna — {}", host, error.message)
                        null
                    }
                    response.statusCode() in 300..399 -> response.headers().firstValue("location").orElse(null)
                    response.statusCode() in 200..299 -> AdvertiserInstagram.handleFrom(response.body())
                    else -> null
                }?.let { it to response.statusCode() }
            }
            .thenCompose { result ->
                val (value, status) = result ?: return@thenCompose CompletableFuture.completedFuture(null)
                if (status in 300..399) {
                    follow(runCatching { uri.resolve(value) }.getOrNull(), hopsLeft - 1)
                } else {
                    CompletableFuture.completedFuture(value)
                }
            }
    }

    private fun remember(domain: String, handle: String?) {
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        cache[domain] = Cached(handle, Instant.now())
    }

    /**
     * Czy host wskazuje na zwykły adres w internecie.
     *
     * Odsiewamy pętlę zwrotną, sieci prywatne i adresy link-local — w tym
     * `169.254.169.254`, spod którego chmury wydają poświadczenia. Bez tego
     * dowolny reklamodawca mógłby podpisem reklamy kazać naszemu serwerowi
     * zapukać do jego własnej sieci wewnętrznej.
     *
     * Nierozwiązywalna nazwa też odpada: nie ma czego pobierać.
     */
    private fun publiclyRoutable(host: String): Boolean =
        runCatching {
            InetAddress.getAllByName(host).let { addresses ->
                addresses.isNotEmpty() && addresses.none(AdvertiserInstagram::isPrivateAddress)
            }
        }.getOrDefault(false)
}
