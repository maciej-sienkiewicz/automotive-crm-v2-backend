package pl.detailing.crm.leads.similar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.UUID

/**
 * Zdarzenie: auto leada jest rozstrzygnięte — rozpoznane, nierozpoznane po próbie
 * albo wpisane ręcznie. To jest właściwy moment na policzenie podobnych zleceń:
 * chwilę WCZEŚNIEJ (przy samym utworzeniu leada) marki jeszcze nie ma, bo
 * rozpoznanie auta samo jest asynchroniczne, i wynik brzmiałby zawsze
 * „nie znamy auta".
 */
data class LeadVehicleResolvedEvent(
    val studioId: UUID,
    val leadId: UUID
)

/**
 * Zapisany wynik doboru podobnych zleceń — jeden wiersz na leada.
 *
 * Format [matches]: pary „visitId;RANGA" rozdzielone znakiem |, w kolejności
 * doboru (ranga, potem świeżość) — dokładnie to, co rozstrzyga krata, i nic
 * ponadto. Kwoty i nazwy usług NIE są tu zapisywane: doczytują się z bazy przy
 * każdym odczycie, więc zlecenie, które w międzyczasie dostało kolejną usługę,
 * nie pokazuje wczorajszej ceny.
 *
 * Wiersz przechowuje więcej pozycji, niż widzi ekran ([SimilarVisitsHandler]
 * przycina przy odczycie): zdjęcie podpowiedzi „X-em" ma dosunąć następną
 * z zapasu, a nie skracać listę do czasu ręcznego odświeżenia.
 */
@Entity
@Table(
    name = "lead_similar_matches",
    indexes = [Index(name = "ix_lead_similar_matches_studio", columnList = "studio_id")]
)
class LeadSimilarMatchesEntity(
    @Id
    @Column(name = "lead_id", columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Powód pustki (SERVICE_NOT_IN_CATALOG / VEHICLE_UNKNOWN) albo null. */
    @Column(name = "empty_reason", length = 40)
    var emptyReason: String? = null,

    @Column(name = "matches", nullable = false, columnDefinition = "text")
    var matches: String = "",

    @Column(name = "computed_at", nullable = false)
    var computedAt: Instant = Instant.now()
) {
    fun parsed(): List<Pair<UUID, MatchTier>> =
        matches.split('|').mapNotNull { entry ->
            val parts = entry.split(';').takeIf { it.size == 2 } ?: return@mapNotNull null
            val visitId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val tier = MatchTier.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            visitId to tier
        }

    companion object {
        fun serialize(matches: List<Pair<UUID, MatchTier>>): String =
            matches.joinToString("|") { (visitId, tier) -> "$visitId;${tier.name}" }
    }
}

@Repository
interface LeadSimilarMatchesRepository : JpaRepository<LeadSimilarMatchesEntity, UUID>

/**
 * Liczy podobne zlecenia w tle, gdy tylko auto leada jest rozstrzygnięte —
 * żeby otwarcie leada zastało wynik gotowy, zamiast kazać na niego czekać.
 *
 * Awaria jest cicha i NIC nie zapisuje: lead bez zapisanego wyniku policzy się
 * leniwie przy pierwszym otwarciu sekcji, więc tło jest przyspieszeniem,
 * nie warunkiem działania.
 */
@Component
class LeadSimilarPrecomputeListener(
    private val handler: SimilarVisitsHandler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onVehicleResolved(event: LeadVehicleResolvedEvent) {
        runCatching {
            handler.computeAndStore(pl.detailing.crm.shared.StudioId(event.studioId), event.leadId)
        }.onFailure {
            log.warn("[SIMILAR_VISITS] Doliczenie w tle dla leada {} nie powiodło się: {}", event.leadId, it.message)
        }
    }
}
