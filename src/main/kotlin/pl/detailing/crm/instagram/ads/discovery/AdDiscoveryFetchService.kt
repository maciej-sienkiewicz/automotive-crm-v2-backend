package pl.detailing.crm.instagram.ads.discovery

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.ads.MetaAdCodec
import pl.detailing.crm.instagram.ads.MetaAdLibraryClient
import pl.detailing.crm.instagram.ads.MetaAdsException
import pl.detailing.crm.instagram.ads.RawMetaAd
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Napełnianie WSPÓLNEGO cache odkrywania: pobranie reklam dla frazy z Meta i
 * zapis stanu na teraz.
 *
 * Świadomie rozdzielone na dwie warstwy jak reszta modułu: samo wywołanie HTTP
 * ([fetchPhrase]) stoi POZA transakcją, a podmiana wierszy ([AdDiscoveryCacheWriter])
 * siedzi w krótkiej transakcji. Transakcja rozpięta wokół odpowiedzi obcego serwera
 * trzymałaby połączenie z bazą przez cały czas jego myślenia.
 *
 * Cały ruch do Meta przechodzi przez tę samą bramkę [MetaAdsCallGate] co nocny sync
 * obserwowanych profili — bo to JEDEN token z jednym limitem 200 wywołań/godz. na
 * całą instalację. Cache po frazie sprawia, że przy dziesiątkach studiów pytających
 * o te same hasła realnych pobrań jest garść.
 */
@Service
class AdDiscoveryFetchService(
    private val client: MetaAdLibraryClient,
    private val phraseRepository: AdDiscoveryPhraseRepository,
    private val cacheWriter: AdDiscoveryCacheWriter,
    @Value("\${meta.ads.discovery.max-pages-per-phrase:5}") private val maxPagesPerPhrase: Int,
    @Value("\${meta.ads.discovery.cache-ttl-hours:18}") cacheTtlHours: Long,
    @Value("\${meta.ads.discovery.retry-minutes:30}") retryMinutes: Long
) {
    private val log = LoggerFactory.getLogger(AdDiscoveryFetchService::class.java)

    private val cacheTtl = Duration.ofHours(cacheTtlHours)

    /** Po nieudanym pobraniu nie ponawiamy przy każdym wejściu — dopiero po tym czasie. */
    private val retryInterval = Duration.ofMinutes(retryMinutes)

    /**
     * Upewnij się, że frazy mają świeże dane w cache; pobierz brakujące/przeterminowane.
     *
     * To jest punkt „on-demand": ktoś pyta o frazę, której nie było albo która
     * zwietrzała, więc pobieramy ją tu i teraz. Fraza świeższa niż TTL nie rusza
     * Meta w ogóle. Fraza po niedawnym błędzie/limicie też czeka [retryInterval],
     * żeby jeden wyczerpany limit nie zamienił się w lawinę prób przy każdym odczycie.
     */
    fun ensureFresh(phrases: Collection<String>) {
        val distinct = phrases.map(AdDiscoveryPhrase::normalize).filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty() || !client.enabled) return

        val known = phraseRepository.findByPhraseIn(distinct).associateBy { it.phrase }
        val now = Instant.now()

        distinct.filter { phrase -> needsFetch(known[phrase], now) }
            .forEach { fetchPhrase(it) }
    }

    private fun needsFetch(entity: AdDiscoveryPhraseEntity?, now: Instant): Boolean {
        val fetchedAt = entity?.lastFetchedAt ?: return true
        val maxAge = if (entity.lastStatus == PhraseFetchStatus.OK) cacheTtl else retryInterval
        return Duration.between(fetchedAt, now) >= maxAge
    }

    /**
     * Jedno pobranie frazy: HTTP poza transakcją, potem podmiana cache w transakcji.
     * Zwraca status, którym można od razu poinformować ekran przy pobraniu on-demand.
     */
    fun fetchPhrase(phrase: String): PhraseFetchStatus {
        val normalized = AdDiscoveryPhrase.normalize(phrase)
        if (normalized.length < AdDiscoveryPhrase.MIN_LENGTH || !client.enabled) return PhraseFetchStatus.OK

        return try {
            val result = client.fetchActiveAdsByTerm(normalized, maxPagesPerPhrase)
            cacheWriter.replacePhrase(normalized, result.ads, result.truncated)
            log.info(
                "Odkrywanie reklam: fraza „{}” -> {} aktywnych reklam{}",
                normalized, result.ads.size, if (result.truncated) " (ucięte — fraza zbyt ogólna)" else ""
            )
            PhraseFetchStatus.OK
        } catch (e: MetaAdsException) {
            val status = when {
                e.errorCode == 10 && e.errorSubcode == 2332002 -> PhraseFetchStatus.NOT_VERIFIED
                e.message?.contains("Limit wywołań") == true -> PhraseFetchStatus.RATE_LIMITED
                else -> PhraseFetchStatus.ERROR
            }
            log.warn("Odkrywanie reklam: fraza „{}” nie pobrana ({}) — {}", normalized, status, e.message)
            cacheWriter.markStatus(normalized, status)
            status
        }
    }
}

/**
 * Zapis stanu cache w krótkiej transakcji — oddzielony od [AdDiscoveryFetchService],
 * żeby transakcja nie obejmowała wywołania HTTP do Meta.
 */
@Service
class AdDiscoveryCacheWriter(
    private val phraseRepository: AdDiscoveryPhraseRepository,
    private val adRepository: AdDiscoveryAdRepository
) {

    /**
     * Podmiana wierszy frazy na aktualny stan biblioteki. Odkrywanie nie prowadzi
     * historii — kasujemy poprzednie reklamy frazy i wstawiamy bieżące. Kasowanie
     * idzie przed wstawianiem, więc unikalny (phrase, ad_archive_id) się nie zderza.
     */
    @Transactional
    fun replacePhrase(phrase: String, ads: List<RawMetaAd>, truncated: Boolean) {
        adRepository.deleteByPhrase(phrase)

        val now = Instant.now()
        // Ta sama reklama bywa na kilku stronach paginacji — bez odsiewu dubel łamie unikalność.
        val deduped = ads.distinctBy { it.adArchiveId }
        deduped.forEach { adRepository.save(toEntity(phrase, it, now)) }

        val entity = phraseRepository.findByPhrase(phrase)
        if (entity == null) {
            phraseRepository.save(
                AdDiscoveryPhraseEntity(
                    id = UUID.randomUUID(),
                    phrase = phrase,
                    lastFetchedAt = now,
                    lastStatus = PhraseFetchStatus.OK,
                    adCount = deduped.size,
                    truncated = truncated
                )
            )
        } else {
            entity.lastFetchedAt = now
            entity.lastStatus = PhraseFetchStatus.OK
            entity.adCount = deduped.size
            entity.truncated = truncated
            entity.updatedAt = now
            phraseRepository.save(entity)
        }
    }

    /** Zapis samego statusu nieudanej próby — bez ruszania reklam, które już są w cache. */
    @Transactional
    fun markStatus(phrase: String, status: PhraseFetchStatus) {
        val now = Instant.now()
        val entity = phraseRepository.findByPhrase(phrase)
        if (entity == null) {
            phraseRepository.save(
                AdDiscoveryPhraseEntity(
                    id = UUID.randomUUID(),
                    phrase = phrase,
                    lastFetchedAt = now,
                    lastStatus = status,
                    adCount = 0,
                    truncated = false
                )
            )
        } else {
            entity.lastStatus = status
            entity.lastFetchedAt = now
            entity.updatedAt = now
            phraseRepository.save(entity)
        }
    }

    private fun toEntity(phrase: String, ad: RawMetaAd, now: Instant) = AdDiscoveryAdEntity(
        id = UUID.randomUUID(),
        phrase = phrase,
        adArchiveId = ad.adArchiveId,
        pageId = ad.pageId,
        pageName = ad.pageName?.trim()?.take(200)?.takeIf { it.isNotBlank() },
        deliveryStart = ad.deliveryStart,
        deliveryStop = ad.deliveryStop,
        reachPl = ad.reachPoland,
        targetLocations = MetaAdCodec.encodeLocations(ad.targetLocations),
        snapshotUrl = ad.snapshotUrl,
        fetchedAt = now
    )
}
