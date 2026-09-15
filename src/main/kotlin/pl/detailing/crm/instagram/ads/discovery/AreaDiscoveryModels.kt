package pl.detailing.crm.instagram.ads.discovery

import pl.detailing.crm.instagram.ads.RawAdLocation

/**
 * Odkrywanie reklamodawców po frazie i obszarze („Kto jeszcze reklamuje się na
 * frazy X, Y w rejonie Poznań, Skórzewo, Suchy Las").
 *
 * Różnica względem kalendarza reklam z tego samego modułu: kalendarz pyta o
 * KONKRETNE strony, które studio już obserwuje. Tu nie znamy stron z góry —
 * pytamy bibliotekę po TREŚCI reklamy (`search_terms`), dostajemy reklamy z całej
 * Polski i dopiero u siebie odfiltrowujemy te, których targetowanie obejmuje
 * wskazany teren.
 *
 * Dwie decyzje architektoniczne, które trzymają to w ryzach przy jednym wspólnym
 * tokenie Meta (limit 200 wywołań/godz. na CAŁĄ instalację, nie na studio):
 *
 *  1. **Cache kluczowany po frazie, wspólny dla wszystkich najemców.** Reklamy dla
 *     „detailing" w Polsce są takie same niezależnie od tego, które studio pyta —
 *     więc pobieramy je raz i dzielimy. Lokalizacja to filtr NA odczycie, nie
 *     parametr pobrania. Dziesięć studiów pytających o tę samą frazę to jedno
 *     wywołanie do Meta, nie dziesięć.
 *  2. **Śledzenie obszaru jest trwałe.** Gdy studio zapisze śledzenie, jego frazy
 *     wchodzą do zestawu odświeżanego cyklicznie (2×/dobę) — do momentu wyłączenia.
 *     Wejście na ekran korzysta wtedy z gotowego cache, a nie odpala Meta.
 */

/** Jak szeroko traktować „reklamuje się w tym rejonie". */
enum class AreaMatchMode {
    /** Reklamodawca musi mieć w targetowaniu DOKŁADNIE wskazaną miejscowość. */
    CITIES_ONLY,

    /**
     * Dodatkowo łapiemy targetowanie szersze niż punkt — województwo obejmujące
     * wskazaną miejscowość albo całą Polskę — bo taka reklama i tak dociera na ten teren.
     */
    INCLUDE_BROADER
}

/**
 * Reklama zredukowana do tego, co potrzebne do filtrowania po obszarze i złożenia
 * wiersza tabeli. Płaski model odczytu — bez encji JPA, żeby logika filtrowania i
 * grupowania (a więc i testy) nie zależała od bazy.
 */
data class DiscoveredAd(
    val adArchiveId: String,
    val pageId: String,
    val pageName: String?,
    /** Emisja trwa (delivery_stop puste). Odkrywanie pobiera tylko aktywne, ale bronimy się i tu. */
    val active: Boolean,
    /** Zasięg reklamy w UE (`eu_total_reach`) — liczba pokazywana w kolumnie „zasięg". */
    val reach: Int?,
    /** Targetowanie reklamodawcy — po nim decydujemy, czy reklama obejmuje wskazany teren. */
    val locations: List<RawAdLocation>,
    /** Adres z reklamy — punkt zaczepienia dla nazwy profilu na Instagramie. */
    val linkCaption: String? = null
)

/** Jeden wiersz tabeli wyników: jeden reklamodawca (strona na Facebooku). */
data class AdvertiserRow(
    val pageId: String,
    val companyName: String,
    val activeAds: Int,
    /** Suma zasięgu w UE reklam trafiających w obszar; null, gdy żadna nie ma danych. */
    val reach: Int?,
    /** Strona reklamodawcy w Bibliotece reklam Meta, zawężona do aktywnych reklam w PL. */
    val adLibraryUrl: String,
    /** Podgląd pojedynczej reklamy — pierwsza z migawką, gdy Meta ją udostępniła. */
    val sampleSnapshotUrl: String?,
    /** Domena firmy złożona z adresów jej reklam; null, gdy kieruje tylko na pośredników. */
    val domain: String? = null,
    /** Nazwa profilu na Instagramie, bez małpy. Null, gdy nie udało się jej ustalić. */
    val instagram: String? = null
)
