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

    /** Powód pustki (SERVICE_NOT_IN_CATALOG / VEHICLE_UNKNOWN / NEEDS_INSPECTION) albo null. */
    @Column(name = "empty_reason", length = 40)
    var emptyReason: String? = null,

    @Column(name = "matches", nullable = false, columnDefinition = "text")
    var matches: String = "",

    @Column(name = "computed_at", nullable = false)
    var computedAt: Instant = Instant.now(),

    /**
     * Wersja REGUŁ, którymi policzono ten wiersz. findFor() preferuje zapisany
     * dobór bezwarunkowo, więc bez tej kolumny żadna zmiana bramek nie dociera
     * do leada z wynikiem — w tym do leadów, od których zaczęła się przebudowa:
     * naprawa niewidoczna tam, gdzie poszła awantura, jest nieodróżnialna od
     * braku naprawy. Wiersz z wersją niższą niż stała w kodzie jest przeliczany
     * przy najbliższym otwarciu. Default konstruktora = wersja bieżąca (świeży
     * zapis JEST bieżący); default KOLUMNY w V129 to 0 — wiersze zastane.
     */
    @Column(name = "rules_version", nullable = false)
    var rulesVersion: Int = CURRENT_RULES_VERSION,

    // ── Wynik pasma cenowego (V132) — produktem sekcji jest przedział, nie lista ──

    /** BAND | SINGLE | EVIDENCE_ONLY | ABSTAIN — patrz [pricing.AnchorVerdict]. */
    @Column(name = "verdict", length = 20)
    var verdict: String? = null,

    /** Kod nazwanego milczenia — patrz [pricing.AbstentionPolicy]. */
    @Column(name = "abstention_code", length = 30)
    var abstentionCode: String? = null,

    @Column(name = "band_min")
    var bandMin: Long? = null,

    @Column(name = "band_median")
    var bandMedian: Long? = null,

    @Column(name = "band_max")
    var bandMax: Long? = null,

    @Column(name = "sample_size")
    var sampleSize: Int? = null,

    /** Wszystkie compsy po tej samej stronie kotwicy — etykieta zamiast przedziału. */
    @Column(name = "one_sided", nullable = false)
    var oneSided: Boolean = false,

    /** Kotwica katalogowa użyta przy doborze, w groszach. */
    @Column(name = "anchor_gross")
    var anchorGross: Long? = null,

    /** (mediana − kotwica) / kotwica — rozbieżność katalog↔historia. */
    @Column(name = "divergence", precision = 6, scale = 3)
    var divergence: java.math.BigDecimal? = null
) {
    /** Jeden zapisany dobór: wizyta, ranga, klasa compa (null dla wpisów sprzed V132). */
    data class StoredMatch(val visitId: UUID, val tier: MatchTier?, val compClass: String?)

    /**
     * Wpisy 2-członowe (visitId;TIER — format sprzed przebudowy) i 3-członowe
     * (visitId;TIER;KLASA) czytają się tak samo: `size >= 2`, nie `== 2` —
     * inaczej stary format wracałby jako CICHY dobór pusty. Ranga, której enum
     * już nie zna (skasowane SAME_MODEL_OTHER_SERVICE/MODEL_HISTORY), daje tier
     * null zamiast wycięcia wpisu — o losie wiersza i tak rozstrzyga rules_version.
     */
    fun parsedMatches(): List<StoredMatch> =
        matches.split('|').mapNotNull { entry ->
            val parts = entry.split(';').takeIf { it.size >= 2 } ?: return@mapNotNull null
            val visitId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return@mapNotNull null
            StoredMatch(
                visitId = visitId,
                tier = MatchTier.entries.firstOrNull { it.name == parts[1] },
                compClass = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            )
        }

    /** Zgodność wsteczna: pary (wizyta, ranga) — wpisy bez znanej rangi wypadają. */
    fun parsed(): List<Pair<UUID, MatchTier>> =
        parsedMatches().mapNotNull { match -> match.tier?.let { match.visitId to it } }

    companion object {
        /**
         * Wersja REGUŁ doboru. Podbijana przy KAŻDEJ zmianie bramek, progów albo
         * kraty — to ona niesie naprawę do leadów, które już mają zapisany wynik.
         * 0 = wiersze sprzed przebudowy; 2 = bramki v2 (osie + skala + pasmo).
         */
        const val CURRENT_RULES_VERSION = 2

        fun serialize(matches: List<Pair<UUID, MatchTier>>): String =
            matches.joinToString("|") { (visitId, tier) -> "$visitId;${tier.name}" }

        fun serializeMatches(matches: List<StoredMatch>): String =
            matches.joinToString("|") { match ->
                "${match.visitId};${match.tier?.name ?: ""};${match.compClass ?: ""}"
            }
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
    private val handler: SimilarVisitsHandler,
    private val suggestionService: LeadServiceSuggestionService,
    private val visionService: pl.detailing.crm.leads.similar.vision.LeadAttachmentVisionService,
    private val rolePreviewGuard: pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onVehicleResolved(event: LeadVehicleResolvedEvent) {
        // Piaskownica podglądu roli nie pyta modelu AI (zdjęcia, dobór, sugestie).
        if (rolePreviewGuard.isSandbox(event.studioId)) return
        val studioId = pl.detailing.crm.shared.StudioId(event.studioId)
        // Kolejność jest istotna trzykrotnie: fakty ze zdjęć zasilają odczyt potrzeby,
        // potrzeba zasila dobór, a sugestie czerpią ceny „wyceny niestandardowej"
        // z podobnych zleceń — więc zdjęcia idą pierwsze, dobór drugi, sugestie trzecie.
        runCatching { visionService.analyzeLeadAttachments(event.studioId, event.leadId) }
            .onFailure { log.warn("[LEAD_VISION] Odczyt załączników leada {} nie powiódł się: {}", event.leadId, it.message) }
        runCatching { handler.computeAndStore(studioId, event.leadId) }
            .onFailure { log.warn("[SIMILAR_VISITS] Doliczenie w tle dla leada {} nie powiodło się: {}", event.leadId, it.message) }
        runCatching { suggestionService.recompute(studioId, event.leadId, force = false) }
            .onFailure { log.warn("[LEAD_SUGGEST] Sugestie w tle dla leada {} nie powiodły się: {}", event.leadId, it.message) }
    }
}
