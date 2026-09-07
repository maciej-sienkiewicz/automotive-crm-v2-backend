package pl.detailing.crm.instagram.ads

/** Jeden pasek w kalendarzu — jedna kampania. */
data class AdBarDto(
    val adId: String,
    val title: String?,
    /** ISO. Data rozpoczęcia emisji wg Meta, także gdy wypada przed oknem kalendarza. */
    val start: String,
    /** ISO albo null, gdy emisja trwa. */
    val stop: String?,
    /** Dni emisji policzone w obrębie roku kalendarza. */
    val days: Int,
    val reach: Int?,
    val platforms: List<String>,
    /**
     * Tor, na którym rysować pasek. Kampanie tego samego profilu emitowane
     * równolegle dostają osobne tory — dzięki temu podwójne liczenie dni widać
     * na ekranie, zamiast trzeba je było przyjąć na wiarę.
     */
    val lane: Int
)

data class AdCalendarRowDto(
    val profileId: String,
    val username: String,
    val isSelf: Boolean,
    /** Strona na Facebooku, po której pytamy o reklamy — do podglądu i zmiany. */
    val facebookPageId: String?,
    val campaigns: Int,
    val activeNow: Int,
    /** Suma dni każdej kampanii osobno: 4 kampanie po 3 dni = 12. */
    val sponsoredDays: Int,
    /** Suma zasięgu w Polsce ze wszystkich kampanii roku. */
    val reachTotal: Int?,
    val lanes: Int,
    val ads: List<AdBarDto>
)

/** Profil, którego nie da się sprawdzić, bo nie wskazano strony na Facebooku. */
data class UnlinkedProfileDto(
    val profileId: String,
    val username: String
)

data class AdCalendarResponse(
    val year: Int,
    /** ISO. Prawa krawędź kalendarza dla roku bieżącego. */
    val today: String,
    val activeToday: Int,
    val rows: List<AdCalendarRowDto>,
    val unlinked: List<UnlinkedProfileDto>,
    /**
     * false = nie ma skonfigurowanego tokena Biblioteki reklam. Front pokazuje
     * wtedy stan „brak danych", a nie pusty rok, który wyglądałby jak stwierdzenie,
     * że konkurencja się nie reklamuje.
     */
    val configured: Boolean
)

data class AdLocationDto(
    val name: String,
    /** country | region | city … — nazewnictwo Meta, przetłumaczone na froncie. */
    val type: String,
    val excluded: Boolean
)

data class AdReachBucketDto(
    val ageRange: String,
    val male: Int,
    val female: Int,
    /** Czy przedział mieści się w wieku ustawionym przez reklamodawcę. */
    val inTargetAge: Boolean
)

data class AdDetailDto(
    val adId: String,
    val profileId: String,
    val username: String,
    val title: String?,
    val start: String,
    val stop: String?,
    val days: Int,
    val active: Boolean,
    /** Zasięg w Polsce — suma rozbicia. */
    val reach: Int?,
    val platforms: List<String>,
    val targetAges: String?,
    val targetGender: String?,
    val locations: List<AdLocationDto>,
    val payer: String?,
    val beneficiary: String?,
    val breakdown: List<AdReachBucketDto>,
    /** Ilu ludzi spoza ustawionego przedziału wieku reklama i tak dosięgła. */
    val outOfTargetAgeReach: Int,
    val snapshotUrl: String?
)

/** Wskazanie strony na Facebooku dla obserwowanego profilu. */
data class LinkFacebookPageRequest(
    val pageId: String,
    val pageName: String?
)

/**
 * Aktywność reklamowa profilu w tygodniowym podsumowaniu — jedna pigułka
 * obok linków do postów. Pojawia się tylko wtedy, gdy było co pokazać.
 */
data class DigestAdDto(
    val adId: String,
    /** STARTED (uruchomił w tym tygodniu) | RUNNING (trwa) | ENDED (skończyła się) */
    val state: String,
    val title: String?,
    /** Dni emisji: dla trwającej — do dziś, dla zakończonej — całość. */
    val days: Int,
    val reach: Int?,
    /** ISO, data rozpoczęcia emisji. */
    val startedOn: String
)

/** Kandydat na stronę reklamodawcy — wynik szukania po nazwie. */
data class PageCandidateDto(
    val pageId: String,
    val pageName: String,
    /** Ile reklam tej strony trafiło w zapytanie — pomaga odróżnić firmę od zbieżnej nazwy. */
    val ads: Int,
    /** ISO. Ostatni znany start emisji. */
    val lastStart: String?
)
