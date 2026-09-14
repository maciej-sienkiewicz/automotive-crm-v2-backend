package pl.detailing.crm.instagram.ads.discovery

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Wynik ostatniej próby pobrania frazy — po nim ekran wie, czemu cache jest pusty. */
enum class PhraseFetchStatus {
    /** Pobrano poprawnie (także gdy Meta zwróciła zero reklam). */
    OK,

    /** Wyczerpany limit wywołań Meta — spróbujemy przy następnym odświeżeniu. */
    RATE_LIMITED,

    /** Konto Meta nie przeszło weryfikacji tożsamości w bibliotece reklam. */
    NOT_VERIFIED,

    /** Inny błąd biblioteki reklam. */
    ERROR
}

/**
 * Wpis frazy we WSPÓLNYM cache odkrywania (jeden na frazę, niezależnie od studia).
 *
 * Fraza to klucz cache: reklamy dla „detailing" w Polsce są takie same dla każdego
 * najemcy, więc pobieramy je raz i dzielimy. [lastFetchedAt] rządzi świeżością —
 * odczyt starszy niż TTL uruchamia pobranie w tle jednej frazy, nie całości.
 */
@Entity
@Table(
    name = "meta_ad_discovery_phrases",
    indexes = [Index(name = "ux_ad_discovery_phrases_phrase", columnList = "phrase", unique = true)]
)
class AdDiscoveryPhraseEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    /** Znormalizowana fraza (małymi, pojedyncze spacje) — klucz dzielony między najemcami. */
    @Column(name = "phrase", nullable = false, length = 200)
    val phrase: String,

    @Column(name = "last_fetched_at", nullable = true, columnDefinition = "timestamp with time zone")
    var lastFetchedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", nullable = false, length = 20)
    var lastStatus: PhraseFetchStatus = PhraseFetchStatus.OK,

    /** Liczba aktywnych reklam w cache po ostatnim pobraniu — do szybkiego podglądu bez zliczania. */
    @Column(name = "ad_count", nullable = false)
    var adCount: Int = 0,

    /**
     * true, gdy Meta zwróciła więcej reklam, niż przeszliśmy (limit stron): fraza
     * jest zbyt ogólna, a tabela — niepełna. Ekran prosi wtedy o doprecyzowanie.
     */
    @Column(name = "truncated", nullable = false)
    var truncated: Boolean = false,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

/**
 * Jedna aktywna reklama w cache odkrywania, przypięta do frazy (nie do studia).
 *
 * W przeciwieństwie do `meta_ad_snapshots` NIE budujemy tu historii: przy każdym
 * odświeżeniu frazy podmieniamy jej wiersze na aktualny stan biblioteki. Odkrywanie
 * odpowiada na pytanie „kto reklamuje się TERAZ", a nie „kto reklamował się kiedyś" —
 * od historii jest kalendarz obserwowanych profili.
 */
@Entity
@Table(
    name = "meta_ad_discovery_ads",
    indexes = [
        Index(name = "ux_ad_discovery_ads_phrase_archive", columnList = "phrase, ad_archive_id", unique = true),
        Index(name = "ix_ad_discovery_ads_phrase", columnList = "phrase")
    ]
)
class AdDiscoveryAdEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    /** Fraza, pod którą Meta zwróciła tę reklamę — ta sama reklama może wisieć pod kilkoma. */
    @Column(name = "phrase", nullable = false, length = 200)
    val phrase: String,

    @Column(name = "ad_archive_id", nullable = false, length = 64)
    val adArchiveId: String,

    @Column(name = "page_id", nullable = false, length = 40)
    val pageId: String,

    @Column(name = "page_name", nullable = true, length = 200)
    val pageName: String? = null,

    @Column(name = "delivery_start", nullable = false)
    val deliveryStart: LocalDate,

    /** NULL = emisja trwa. Odkrywanie pobiera tylko aktywne, więc zwykle NULL. */
    @Column(name = "delivery_stop", nullable = true)
    val deliveryStop: LocalDate? = null,

    /**
     * Zasięg w UE (`eu_total_reach`) — liczba pokazywana w tabeli.
     *
     * Odkrywanie bierze zasięg z tego lekkiego pola, a NIE z rozbicia PL: rozbicia
     * (`age_country_gender_reach_breakdown`) nie da się pobrać hurtowo dla szerokiej
     * frazy — Meta odrzuca zbyt duży payload. Kalendarz obserwowanych profili dalej
     * pokazuje zasięg PL, bo pyta o pojedyncze strony i może brać pełne pola.
     */
    @Column(name = "reach_eu", nullable = true)
    val reachEu: Int? = null,

    /** Trójki `nazwa;typ;wykluczona(0|1)` rozdzielone `|` — kodowane [pl.detailing.crm.instagram.ads.MetaAdCodec]. */
    @Column(name = "target_locations", nullable = false, columnDefinition = "text")
    val targetLocations: String = "",

    @Column(name = "snapshot_url", nullable = true, columnDefinition = "text")
    val snapshotUrl: String? = null,

    /**
     * Adres, na który reklama kieruje („folia-samochodowa.pl”) — jedyne wskazanie
     * na własną stronę reklamodawcy, jakie niesie `ads_archive`. Z niego wyprowadzamy
     * nazwę profilu na Instagramie, bo tej Meta nie udostępnia.
     */
    @Column(name = "link_caption", nullable = true, length = 300)
    val linkCaption: String? = null,

    @Column(name = "fetched_at", nullable = false, columnDefinition = "timestamp with time zone")
    val fetchedAt: Instant = Instant.now()
)

/**
 * Trwałe śledzenie obszaru należące do jednego studia.
 *
 * Gdy istnieje i jest [active], jego frazy wchodzą do cyklicznego odświeżania —
 * to jest różnica między „pokaż raz" a „śledź na stałe", której chciał właściciel.
 * Frazy i lokalizacje trzymamy jako listy rozdzielone `|` (jak reszta modułu list
 * krótkich wartości), bo odczytujemy je zawsze w całości.
 */
@Entity
@Table(
    name = "meta_ad_location_trackings",
    indexes = [
        Index(name = "ix_ad_location_trackings_studio", columnList = "studio_id"),
        Index(name = "ix_ad_location_trackings_active", columnList = "active")
    ]
)
class AdLocationTrackingEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Nazwa własna nadana przez studio, np. „Detailing — aglomeracja poznańska". */
    @Column(name = "label", nullable = false, length = 120)
    var label: String,

    /**
     * Identyfikatory fraz z katalogu, których to studio NIE chce śledzić, rozdzielone `|`.
     *
     * Odwrotnie niż wcześniej: nie trzymamy listy fraz, tylko listę odjęć od
     * [AdDiscoveryCatalog]. Dzięki temu fraza dołożona do katalogu włącza się
     * wszystkim sama, bez migracji czyichkolwiek ustawień.
     */
    @Column(name = "excluded_phrase_ids", nullable = false, columnDefinition = "text")
    var excludedPhraseIds: String = "",

    /** Miejscowości rejonu rozdzielone `|`, tak jak wpisał je człowiek. */
    @Column(name = "locations", nullable = false, columnDefinition = "text")
    var locations: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "match_mode", nullable = false, length = 20)
    var matchMode: AreaMatchMode = AreaMatchMode.INCLUDE_BROADER,

    /** false = śledzenie wstrzymane: frazy wypadają z cyklicznego odświeżania. */
    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_by_user_id", nullable = false, columnDefinition = "uuid")
    val createdByUserId: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)


/**
 * Wykluczony reklamodawca — jedna tabela na dwa poziomy.
 *
 * [studioId] `null` znaczy wykluczenie GLOBALNE, ustawione przez administratora
 * aplikacji: bot, hurtownia, profil zza granicy trafiający w polskie frazy.
 * Takiego wiersza nie widzi żadne studio.
 *
 * [studioId] wypełnione to czarna lista jednego studia: firma bywa legalnym
 * reklamodawcą, a mimo to nie jest niczyją konkurencją — dostawca chemii, sieć
 * myjni, sąsiad z innej branży.
 *
 * Trzymamy [pageName] obok numeru, bo lista złożona z samych numerów jest nie do
 * przejrzenia, a nazwy nie da się odtworzyć po wykluczeniu strony z wyników.
 */
@Entity
@Table(
    name = "meta_ad_advertiser_blocks",
    indexes = [Index(name = "ix_ad_blocks_studio", columnList = "studio_id")]
)
class AdvertiserBlockEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    /** null = wykluczenie globalne (administrator aplikacji). */
    @Column(name = "studio_id", nullable = true, columnDefinition = "uuid")
    val studioId: UUID? = null,

    @Column(name = "page_id", nullable = false, length = 40)
    val pageId: String,

    /** Nazwa strony w chwili wykluczenia — żeby listę dało się przejrzeć po ludzku. */
    @Column(name = "page_name", nullable = true, length = 200)
    var pageName: String? = null,

    /** Po co wykluczony: „bot", „hurtownia”, notatka studia. */
    @Column(name = "reason", nullable = true, length = 300)
    var reason: String? = null,

    @Column(name = "created_by_user_id", nullable = true, columnDefinition = "uuid")
    val createdByUserId: UUID? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)
