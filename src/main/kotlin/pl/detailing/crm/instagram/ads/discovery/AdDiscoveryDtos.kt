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
    val sampleSnapshotUrl: String?
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
    val advertisers: List<AdvertiserRowDto>,
    /** Suma aktywnych reklam we wszystkich wierszach — szybka etykieta nad tabelą. */
    val totalActiveAds: Int,
    val phraseStatuses: List<PhraseStatusDto>
)

/** Zapisane śledzenie obszaru studia. */
data class LocationTrackingDto(
    val id: String,
    val label: String,
    val phrases: List<String>,
    val locations: List<String>,
    val matchMode: AreaMatchMode,
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String
)

/** Utworzenie/edycja śledzenia obszaru. */
data class SaveLocationTrackingRequest(
    val label: String,
    val phrases: List<String>,
    val locations: List<String>,
    /** Domyślnie [AreaMatchMode.INCLUDE_BROADER] — bo tak intuicyjnie rozumie się „w rejonie". */
    val matchMode: AreaMatchMode? = null,
    /** Domyślnie true przy tworzeniu; przy edycji pozwala wstrzymać śledzenie. */
    val active: Boolean? = null
)

/** Podgląd na żywo bez zapisu — „pokaż mi, kto się reklamuje", zanim zdecyduję o śledzeniu. */
data class AreaPreviewRequest(
    val phrases: List<String>,
    val locations: List<String>,
    val matchMode: AreaMatchMode? = null
)
