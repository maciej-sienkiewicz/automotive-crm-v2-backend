package pl.detailing.crm.instagram.ads.discovery

/** Jeden wiersz tabeli wyników — jedna firma reklamująca się w rejonie. */
data class AdvertiserRowDto(
    val pageId: String,
    val companyName: String,
    /** Ile aktywnych reklam tej firmy trafia w obszar. */
    val activeAds: Int,
    /** Łączny zasięg tych reklam w UE (eu_total_reach); null, gdy Meta nie podała liczby. */
    val reach: Int?,
    /** Link do strony firmy w Bibliotece reklam Meta (aktywne reklamy, PL). */
    val adLibraryUrl: String,
    /** Podgląd pojedynczej reklamy — null, gdy żadna nie ma migawki. */
    val sampleSnapshotUrl: String?,
    /**
     * Nazwa profilu na Instagramie, bez małpy. Meta jej nie podaje — wyprowadzamy
     * ją z adresu, na który kieruje reklama, więc bywa pusta i to jest normalny wynik.
     */
    val instagram: String? = null
)

/** Status frazy we wspólnym cache — po nim ekran wie, czemu tabela jest pusta lub niepełna. */
data class PhraseStatusDto(
    val phrase: String,
    /** OK | RATE_LIMITED | NOT_VERIFIED | ERROR | PENDING (jeszcze nie pobrana). */
    val status: String,
    val adCount: Int,
    /** true = fraza zbyt ogólna, biblioteka miała więcej reklam niż przeszliśmy. */
    val truncated: Boolean,
    /** ISO albo null, gdy frazy nie pobrano jeszcze ani razu. */
    val lastFetchedAt: String?
)

/**
 * Wyniki odkrywania obszaru — wspólny kształt dla podglądu na żywo i dla zapisanego
 * śledzenia. To jest tabela z zadania: kto, ile aktywnych reklam, jaki zasięg.
 */
data class AreaResultsDto(
    val phrases: List<String>,
    val locations: List<String>,
    val matchMode: AreaMatchMode,
    /**
     * false = brak skonfigurowanego tokena Biblioteki reklam. Ekran pokazuje wtedy
     * „brak danych", a nie pustą tabelę, która wyglądałaby jak „nikt się nie reklamuje".
     */
    val configured: Boolean,
    /** ISO. Kiedy złożono te wyniki (dane pochodzą z cache, nie z chwili odczytu). */
    val generatedAt: String,
    /** STRONA wyników, nie całość — reklamodawców w rejonie potrafi być kilkuset. */
    val advertisers: List<AdvertiserRowDto>,
    /** Numer strony liczony od zera. */
    val page: Int,
    val pageSize: Int,
    /** Wszyscy widoczni reklamodawcy, nie tylko ta strona — z tego liczy się liczbę stron. */
    val totalAdvertisers: Int,
    /** Suma aktywnych reklam WSZYSTKICH widocznych reklamodawców, nie tylko tej strony. */
    val totalActiveAds: Int,
    /** Ilu reklamodawców odpadło przez wykluczenia — bez tego krótka tabela nie mówi dlaczego. */
    val hiddenAdvertisers: Int,
    val phraseStatuses: List<PhraseStatusDto>
)

/**
 * Ustawienia rejonu jednego studia. Jeden zestaw, nie lista — po przejściu na
 * wspólny katalog fraz nazwane śledzenia różniły się już wyłącznie miejscowościami.
 */
data class AreaSettingsDto(
    val locations: List<String>,
    val matchMode: AreaMatchMode,
    /** Identyfikatory fraz z katalogu, które to studio odznaczyło. */
    val excludedPhraseIds: List<String>,
    /** Ile fraz katalogu zostaje po odznaczeniach — jedna liczba zamiast liczenia na ekranie. */
    val trackedPhraseCount: Int,
    /** ISO albo null, gdy studio jeszcze nic nie ustawiło. */
    val updatedAt: String?
)

data class SaveAreaSettingsRequest(
    val locations: List<String>,
    val matchMode: AreaMatchMode? = null,
    /**
     * Frazy ODZNACZONE przez studio, nie wybrane. Katalog jest zamknięty i ustala go
     * administrator aplikacji; studio może z niego tylko odejmować.
     */
    val excludedPhraseIds: List<String> = emptyList()
)

/** Jedna fraza katalogu na ekranie ustawień — z zaznaczeniem, czy studio ją śledzi. */
data class CatalogPhraseDto(
    val id: String,
    val text: String,
    /** Klucz grupy (np. FOLIE) — do stabilnego układu, niezależnego od tłumaczenia. */
    val group: String,
    val groupLabel: String
)

/**
 * Katalog fraz podany ekranowi w całości.
 *
 * Ekran nie dostaje listy „co śledzisz", tylko pełen katalog i listę odznaczeń —
 * tak samo jak trzyma to baza. Dzięki temu fraza dołożona przez administratora
 * pojawia się wszystkim sama, bez migracji czyichkolwiek ustawień.
 */
data class PhraseCatalogDto(
    val phrases: List<CatalogPhraseDto>
)

/** Ukryty reklamodawca na czarnej liście studia. */
data class BlockedAdvertiserDto(
    val pageId: String,
    val pageName: String?,
    val reason: String?,
    val createdAt: String
)

data class BlockAdvertiserRequest(
    val pageId: String,
    /** Nazwa w chwili ukrycia — bez niej lista jest ciągiem numerów. */
    val pageName: String? = null,
    val reason: String? = null
)
