package pl.detailing.crm.instagram.ads.discovery

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ads.MetaAdCodec
import pl.detailing.crm.instagram.ads.MetaAdLibraryClient
import java.time.Instant

/**
 * Złożenie tabeli odkrywania: frazy + rejon + tryb → lista firm.
 *
 * Odczyt idzie z WSPÓLNEGO cache, ale najpierw dociąga on-demand frazy, których w
 * cache nie było albo zwietrzały ([AdDiscoveryFetchService.ensureFresh]) — to
 * jedyne miejsce, gdzie odczyt może ruszyć Meta, i robi to POZA transakcją.
 * Właściwe czytanie i grupowanie to już tylko baza i pamięć.
 *
 * Ta sama metoda obsługuje podgląd na żywo i zapisane śledzenie — różni je tylko
 * to, skąd biorą się frazy/rejon (z żądania albo z encji śledzenia).
 */
@Service
class AdDiscoveryReadService(
    private val fetchService: AdDiscoveryFetchService,
    private val phraseRepository: AdDiscoveryPhraseRepository,
    private val adRepository: AdDiscoveryAdRepository,
    private val client: MetaAdLibraryClient,
    @Value("\${meta.ads.discovery.max-phrases-per-tracking:10}") private val maxPhrases: Int
) {

    fun results(phrases: List<String>, locations: List<String>, mode: AreaMatchMode): AreaResultsDto {
        // Ten sam limit fraz co przy zapisie śledzenia — także podgląd na żywo nie
        // może rozjechać wspólnego budżetu wywołań Meta jednym żądaniem.
        val normalizedPhrases = phrases.mapNotNull(AdDiscoveryPhrase::normalizeValid).distinct().take(maxPhrases)
        val cleanLocations = locations.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        if (normalizedPhrases.isEmpty()) {
            return AreaResultsDto(
                phrases = emptyList(),
                locations = cleanLocations,
                matchMode = mode,
                configured = client.enabled,
                generatedAt = Instant.now().toString(),
                advertisers = emptyList(),
                totalActiveAds = 0,
                phraseStatuses = emptyList()
            )
        }

        // On-demand: dociągnij brakujące/przeterminowane frazy do wspólnego cache.
        fetchService.ensureFresh(normalizedPhrases)

        val phraseEntities = phraseRepository.findByPhraseIn(normalizedPhrases).associateBy { it.phrase }

        // Ta sama reklama bywa w cache pod kilkoma frazami — odsiewamy po id, żeby
        // nie liczyć jej dwa razy w „ile aktywnych reklam" ani w sumie zasięgu.
        val discovered = adRepository.findByPhraseIn(normalizedPhrases)
            .distinctBy { it.adArchiveId }
            .map { it.toDiscovered() }

        val rows = AreaAdvertiserSummary.summarize(discovered, cleanLocations, mode).map { it.toDto() }

        return AreaResultsDto(
            phrases = normalizedPhrases,
            locations = cleanLocations,
            matchMode = mode,
            configured = client.enabled,
            generatedAt = Instant.now().toString(),
            advertisers = rows,
            totalActiveAds = rows.sumOf { it.activeAds },
            phraseStatuses = normalizedPhrases.map { phrase -> phraseStatus(phrase, phraseEntities[phrase]) }
        )
    }

    private fun AdDiscoveryAdEntity.toDiscovered() = DiscoveredAd(
        adArchiveId = adArchiveId,
        pageId = pageId,
        pageName = pageName,
        active = deliveryStop == null,
        reach = reachEu,
        snapshotUrl = snapshotUrl,
        locations = MetaAdCodec.decodeLocations(targetLocations)
    )

    private fun AdvertiserRow.toDto() = AdvertiserRowDto(
        pageId = pageId,
        companyName = companyName,
        activeAds = activeAds,
        reach = reach,
        adLibraryUrl = adLibraryUrl,
        sampleSnapshotUrl = sampleSnapshotUrl
    )

    private fun phraseStatus(phrase: String, entity: AdDiscoveryPhraseEntity?) = PhraseStatusDto(
        phrase = phrase,
        // Brak wpisu = jeszcze nie pobrano (np. token wyłączony, więc ensureFresh nic nie zrobił).
        status = entity?.lastStatus?.name ?: "PENDING",
        adCount = entity?.adCount ?: 0,
        truncated = entity?.truncated ?: false,
        lastFetchedAt = entity?.lastFetchedAt?.toString()
    )
}
