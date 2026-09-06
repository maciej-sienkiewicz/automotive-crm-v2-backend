package pl.detailing.crm.instagram.ads

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Migawka jednej reklamy z Biblioteki reklam Meta.
 *
 * Meta nie prowadzi historii: `ads_archive` zwraca stan na teraz, a po roku
 * reklama znika z biblioteki. Wszystko, co pokazujemy w kalendarzu i w Pulsie,
 * pochodzi więc z NASZYCH zapisów — bez nich nie wiedzielibyśmy ani że reklama
 * się skończyła, ani że kiedykolwiek istniała.
 *
 * Pola złożone (lokalizacje, rozbicie zasięgu) leżą jako tekst z separatorami,
 * bo są odczytywane w całości i nigdy nie filtrujemy po ich wnętrzu.
 */
@Entity
@Table(
    name = "meta_ad_snapshots",
    indexes = [
        Index(name = "ux_meta_ad_snapshots_archive_id", columnList = "ad_archive_id", unique = true),
        Index(name = "ix_meta_ad_snapshots_profile_start", columnList = "profile_id, delivery_start")
    ]
)
class MetaAdSnapshotEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    /** Identyfikator reklamy w bibliotece Meta — klucz naturalny przy ponownym odczycie. */
    @Column(name = "ad_archive_id", nullable = false, length = 64)
    val adArchiveId: String,

    @Column(name = "page_id", nullable = false, length = 40)
    val pageId: String,

    /** Globalny profil Instagrama, do którego przypięliśmy tę stronę na Facebooku. */
    @Column(name = "profile_id", nullable = false, columnDefinition = "uuid")
    val profileId: UUID,

    @Column(name = "title", nullable = true, columnDefinition = "text")
    var title: String? = null,

    @Column(name = "delivery_start", nullable = false)
    var deliveryStart: LocalDate,

    /** NULL = emisja trwa. Data pojawia się dopiero, gdy Meta ją wpisze. */
    @Column(name = "delivery_stop", nullable = true)
    var deliveryStop: LocalDate? = null,

    @Column(name = "reach_eu", nullable = true)
    var reachEu: Int? = null,

    /** Zasięg policzony z rozbicia dla Polski — to jest liczba, którą pokazujemy. */
    @Column(name = "reach_pl", nullable = true)
    var reachPl: Int? = null,

    /** FACEBOOK,INSTAGRAM,MESSENGER,AUDIENCE_NETWORK,THREADS */
    @Column(name = "platforms", nullable = false, length = 120)
    var platforms: String = "",

    @Column(name = "target_ages", nullable = true, length = 20)
    var targetAges: String? = null,

    @Column(name = "target_gender", nullable = true, length = 12)
    var targetGender: String? = null,

    /** Trójki `nazwa;typ;wykluczona(0|1)` rozdzielone `|`. */
    @Column(name = "target_locations", nullable = false, columnDefinition = "text")
    var targetLocations: String = "",

    @Column(name = "payer", nullable = true, length = 200)
    var payer: String? = null,

    @Column(name = "beneficiary", nullable = true, length = 200)
    var beneficiary: String? = null,

    /** Czwórki `przedział;M;K;nieokreślona` rozdzielone `|`, wyłącznie dla PL. */
    @Column(name = "reach_breakdown", nullable = false, columnDefinition = "text")
    var reachBreakdown: String = "",

    @Column(name = "snapshot_url", nullable = true, columnDefinition = "text")
    var snapshotUrl: String? = null,

    @Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    val firstSeenAt: Instant = Instant.now(),

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    var lastSeenAt: Instant = Instant.now(),

    /**
     * Kiedy nasz odczyt zobaczył, że [deliveryStop] przestało być NULL.
     *
     * To jest data zdarzenia „zakończył reklamę" w Pulsie — świadomie data
     * WYKRYCIA, nie deklarowana data wyłączenia: Meta może ją wpisać z opóźnieniem,
     * a my nie mamy jak odróżnić jednego od drugiego.
     */
    @Column(name = "ended_detected_at", nullable = true, columnDefinition = "timestamp with time zone")
    var endedDetectedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
