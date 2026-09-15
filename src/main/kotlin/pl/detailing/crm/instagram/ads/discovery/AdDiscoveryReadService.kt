package pl.detailing.crm.instagram.ads.discovery

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ads.AdvertiserInstagramResolver
import pl.detailing.crm.instagram.ads.MetaAdCodec
import pl.detailing.crm.instagram.ads.MetaAdLibraryClient
import pl.detailing.crm.shared.StudioId
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
    private val instagramResolver: AdvertiserInstagramResolver,
    private val blockService: AdvertiserBlockService,
    private val settingsService: AdAreaSettingsService,
    private val igLookupService: pl.detailing.crm.instagram.ads.discovery.ig.MetaIgLookupService,
    @Value("\${meta.ads.discovery.results-page-size:10}") private val defaultPageSize: Int
) {

    private companion object {
        const val MAX_PAGE_SIZE = 50
    }

    /**
     * Tabela dla jednego studia.
     *
     * Frazy biorą się z KATALOGU pomniejszonego o odznaczenia studia — nie z wejścia
     * użytkownika. Dzięki temu liczba unikalnych fraz w całej instalacji jest z góry
     * znana i równa katalogowi, a nie rośnie z liczbą najemców.
     */
    fun results(studioId: StudioId, page: Int = 0, pageSize: Int? = null): AreaResultsDto {
        val settings = settingsService.get(studioId)
        val size = (pageSize ?: defaultPageSize).coerceIn(1, MAX_PAGE_SIZE)
        val wanted = page.coerceAtLeast(0)

        val normalizedPhrases = AdDiscoveryCatalog.phrasesExcept(settings.excludedPhraseIds)
            .mapNotNull(AdDiscoveryPhrase::normalizeValid)
            .distinct()
        val cleanLocations = settings.locations.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val mode = settings.matchMode

        if (normalizedPhrases.isEmpty() || cleanLocations.isEmpty()) {
            return AreaResultsDto(
                phrases = emptyList(),
                locations = cleanLocations,
                matchMode = mode,
                configured = client.enabled,
                generatedAt = Instant.now().toString(),
                advertisers = emptyList(),
                page = 0,
                pageSize = size,
                totalAdvertisers = 0,
                totalActiveAds = 0,
                hiddenAdvertisers = 0,
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

        // Bez filtra i z filtrem, żeby dało się powiedzieć „ukryto N" — sama krótsza
        // tabela nie odróżnia „nikt się nie reklamuje" od „wszystkich ukryłeś".
        val visible = AreaAdvertiserSummary.summarize(discovered, cleanLocations, mode)
        val blocked = blockService.blockedPageIds(studioId)
        val shown = AreaAdvertiserSummary.summarize(discovered, cleanLocations, mode, blocked)
        // Nazwy IG dociągamy TYLKO dla widocznej strony: każda nieznana domena to
        // pobranie cudzej strony WWW, a nikt nie ogląda czterystu wierszy naraz.
        val pages = if (shown.isEmpty()) 1 else (shown.size + size - 1) / size
        val safePage = wanted.coerceAtMost(pages - 1)
        val slice = shown.drop(safePage * size).take(size)
        val rows = withInstagram(slice).map { it.toDto() }

        return AreaResultsDto(
            phrases = normalizedPhrases,
            locations = cleanLocations,
            matchMode = mode,
            configured = client.enabled,
            generatedAt = Instant.now().toString(),
            advertisers = rows,
            page = safePage,
            pageSize = size,
            totalAdvertisers = shown.size,
            totalActiveAds = shown.sumOf { it.activeAds },
            hiddenAdvertisers = visible.size - shown.size,
            phraseStatuses = normalizedPhrases.map { phrase -> phraseStatus(phrase, phraseEntities[phrase]) }
        )
    }

    /**
     * Dokleja nazwy profili na Instagramie — wszystkie domeny tabeli jednym
     * wywołaniem, bo resolver i tak trzyma wyniki w pamięci i zna własny budżet czasu.
     *
     * Krok ozdobny: gdy cokolwiek pójdzie nie tak, tabela wraca nietknięta. Bez nazwy
     * IG wiersz nadal mówi, kto się reklamuje i z jakim zasięgiem — a to jest sedno ekranu.
     */
    private fun withInstagram(rows: List<AdvertiserRow>): List<AdvertiserRow> {
        /*
         * Trzy źródła nazwy profilu, w kolejności od najpewniejszego:
         *
         *   1. PODPIS REKLAMY — reklama kierująca wprost na instagram.com/nazwa.
         *      Pochodzi od samego reklamodawcy, ustalone już w AreaAdvertiserSummary.
         *   2. BIBLIOTEKA REKLAM — pole `ig_username` odczytane ze strony
         *      reklamodawcy przez sidecar. Podaje je Meta, więc jest to nazwa
         *      z pierwszej ręki; czytamy wyłącznie to, co już zapisane w bazie,
         *      bo ustalanie trwa kilkanaście sekund i dzieje się w tle.
         *   3. STRONA FIRMY — link do Instagrama wyłuskany ze stopki. Najsłabsze,
         *      bo zgadujemy, który z linków na cudzej stronie należy do niej samej.
         *
         * Każdy kolejny krok dotyka wyłącznie wierszy, dla których poprzednie
         * nic nie ustaliły.
         */
        val fromLibrary = runCatching { igLookupService.known(rows.map { it.pageId }) }
            .getOrDefault(emptyMap())

        val afterLibrary = rows.map { row ->
            if (row.instagram != null) row
            else fromLibrary[row.pageId]?.let { row.copy(instagram = it) } ?: row
        }

        if (afterLibrary.none { it.domain != null && it.instagram == null }) return afterLibrary

        val fromSite = runCatching { instagramResolver.resolve(afterLibrary.map { it.domain }) }
            .getOrDefault(emptyMap())
        if (fromSite.isEmpty()) return afterLibrary

        return afterLibrary.map { row ->
            if (row.instagram != null) row
            else fromSite[row.domain]?.let { row.copy(instagram = it) } ?: row
        }
    }

    private fun AdDiscoveryAdEntity.toDiscovered() = DiscoveredAd(
        adArchiveId = adArchiveId,
        pageId = pageId,
        pageName = pageName,
        active = deliveryStop == null,
        reach = reachEu,
        locations = MetaAdCodec.decodeLocations(targetLocations),
        linkCaption = linkCaption
    )

    private fun AdvertiserRow.toDto() = AdvertiserRowDto(
        pageId = pageId,
        companyName = companyName,
        activeAds = activeAds,
        reach = reach,
        adLibraryUrl = adLibraryUrl,
        sampleSnapshotUrl = sampleSnapshotUrl,
        instagram = instagram
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
