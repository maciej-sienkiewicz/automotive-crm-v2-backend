package pl.detailing.crm.leads.similar

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.service.taxonomy.ServiceScope
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.segment.VehicleSegmentService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.time.Instant
import java.util.UUID

/** Jedno podobne zlecenie, gotowe do pokazania. Wszystkie pola pochodzą z bazy. */
data class SimilarVisitDto(
    val visitId: String,
    val visitNumber: String,
    val vehicle: String,
    val services: List<String>,
    /**
     * PEŁNA kwota zlecenia (decyzja właściciela produktu), liczona tak, jak liczy
     * ją sama wizyta — bez pozycji odrzuconych i czekających na zgodę klienta.
     * Przy zleceniu wielousługowym wykaz usług obok mówi, co ta kwota obejmuje.
     */
    val totalGross: Long,
    /** Data zakończenia, a gdy zlecenie trwa — planowana. */
    val date: Instant,
    /**
     * Kwota nie jest jeszcze ostateczna: zlecenie w toku dobiera usługi do samego
     * wydania auta. Bez tego oznaczenia liczba na ekranie udawałaby fakt.
     */
    val priceProvisional: Boolean,
    /** Ranga z [MatchTier] — auto × usługa. */
    val matchTier: String
)

data class SimilarVisitsDto(
    val items: List<SimilarVisitDto>,
    /** Ile zleceń studio ma w ogóle w indeksie — odróżnia „nic nie pasuje" od „nie ma czego szukać". */
    val indexedVisits: Long,
    /**
     * Powód pustki, gdy nie wynika ona z ubóstwa historii:
     * SERVICE_NOT_IN_CATALOG — klient pyta o robotę spoza cennika (nie podpowiadamy cen),
     * VEHICLE_UNKNOWN — lead nie ma rozpoznanego auta.
     * Null, gdy lista nie jest pusta albo pustka znaczy po prostu „brak trafień".
     */
    val emptyReason: String? = null
)

/**
 * Odpowiada na pytanie „co robiliśmy dla takiego auta i takiej roboty".
 *
 * ŚCIEŻKA KLIKNIĘCIA NIE WOŁA MODELU I NICZEGO NIE OSADZA. Cała inteligencja
 * pracuje wcześniej i zapisuje wyniki:
 *  - rodzina każdej nazwy usługi — raz na nazwę (service_families),
 *  - segment każdego modelu auta — raz na model (vehicle_segments),
 *  - stempel każdego zlecenia — uzgadniaczem (visit_index_state + sygnatury),
 *  - intencja leada — raz na leada, przy PIERWSZYM otwarciu sekcji (jedyne
 *    wywołanie modelu, na jakie kliknięcie może jeszcze trafić).
 * W czasie zapytania zostaje SQL po kolumnach i krata w [SimilarVisitMatcher].
 *
 * Dlaczego nie bliskość wektorowa: baza wektorowa bez progu nie odrzuca niczego,
 * a z progiem odrzuca w poprzek — „mycie", „pranie tapicerki" i „detailing wnętrza"
 * są sobie tekstowo bliższe niż „PPF przód" i „Full front", które są tą samą robotą.
 * Zamknięta taksonomia rodzin rozstrzyga to raz, przy indeksowaniu, i jest
 * testowalna bez wołania modelu.
 */
@Service
class SimilarVisitsHandler(
    private val leadRepository: LeadRepository,
    private val visitRepository: SimilarVisitReadRepository,
    private val feedbackRepository: VisitMatchFeedbackRepository,
    private val indexStateRepository: VisitIndexStateRepository,
    private val signatureRepository: VisitServiceSignatureRepository,
    private val intentService: LeadServiceIntentService,
    private val segmentService: VehicleSegmentService,
    @Value("\${crm.ai.similar-visits.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.similar-visits.max-results:6}") private val maxResults: Int,
    @Value("\${crm.ai.similar-visits.max-candidates:400}") private val maxCandidates: Int
) {
    @Transactional(readOnly = true)
    fun findFor(studioId: StudioId, leadId: UUID): SimilarVisitsDto {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val indexed = indexStateRepository.countByStudioId(studioId.value)
        if (!enabled || indexed == 0L) return SimilarVisitsDto(emptyList(), indexed)

        val brandKey = lead.vehicleBrand?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val modelKey = lead.vehicleModel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val segment = runCatching { segmentService.classify(lead.vehicleBrand, lead.vehicleModel) }
            .getOrNull()?.sizeSegment?.name?.takeIf { it != "UNKNOWN" }

        // Krata zaczyna się od auta: bez modelu i bez segmentu żadna ranga nie
        // istnieje. Pokazanie „czegokolwiek" byłoby udawaniem podobieństwa.
        if ((brandKey == null || modelKey == null) && segment == null) {
            return SimilarVisitsDto(emptyList(), indexed, emptyReason = REASON_VEHICLE_UNKNOWN)
        }

        // Jedyne miejsce, gdzie kliknięcie może jeszcze trafić na model językowy —
        // pierwszy raz dla tego leada; potem intencja wraca z dziennika. Awaria
        // modelu degraduje do samej historii auta zamiast wywracać sekcję.
        val intent = intentService.intentFor(studioId, leadId, lead.initialMessage)
            ?: LeadServiceIntent(ServiceIntentStatus.NO_SERVICE, emptySet(), emptySet(), ServiceScope.UNKNOWN)

        // Decyzja właściciela produktu: robota spoza cennika = ŻADNYCH cen.
        // Cena innej roboty podana pewnym głosem jest gorsza niż brak podpowiedzi.
        if (intent.status == ServiceIntentStatus.NOT_IN_CATALOG) {
            return SimilarVisitsDto(emptyList(), indexed, emptyReason = REASON_SERVICE_NOT_IN_CATALOG)
        }

        val candidates = indexStateRepository.findCandidates(
            studioId = studioId.value,
            brandKey = brandKey ?: NO_MATCH_KEY,
            modelKey = modelKey ?: NO_MATCH_KEY,
            sizeSegment = segment,
            version = VisitSimilarityIndexer.CURRENT_SIGNATURE_VERSION,
            pageable = PageRequest.of(0, maxCandidates)
        )
        if (candidates.isEmpty()) return SimilarVisitsDto(emptyList(), indexed)

        val signatures = signatureRepository.findByVisitIdIn(candidates.map { it.visitId })
            .groupBy { it.visitId }

        // Zdjęta podpowiedź nie wraca; odsiew idzie PRZED przycięciem, żeby na
        // zwolnione miejsce weszło następne zlecenie zamiast luki.
        val dismissed = feedbackRepository.findByLeadId(leadId).map { it.visitId }.toSet()

        val graded = candidates.mapNotNull { candidate ->
            if (candidate.visitId in dismissed) return@mapNotNull null
            SimilarVisitMatcher.grade(
                candidate = candidate,
                signatures = signatures[candidate.visitId].orEmpty(),
                intent = intent,
                leadBrandKey = brandKey,
                leadModelKey = modelKey,
                leadSegment = segment
            )?.let { tier -> Triple(candidate.visitId, tier, candidate.happenedAt) }
        }
            // Ranga przed świeżością: bliższe dopasowanie bije nowsze zlecenie.
            .sortedWith(
                compareBy<Triple<UUID, MatchTier, Instant?>> { it.second.ordinal }
                    .thenByDescending { it.third ?: Instant.EPOCH }
            )
            .take(maxResults)

        if (graded.isEmpty()) return SimilarVisitsDto(emptyList(), indexed)

        val visits = visitRepository
            .findByStudioIdAndIdIn(studioId.value, graded.map { it.first })
            .associateBy { it.id }

        return SimilarVisitsDto(
            items = graded.mapNotNull { (visitId, tier, _) ->
                visits[visitId]?.let { toDto(it, tier) }
            },
            indexedVisits = indexed
        )
    }

    /**
     * Zdejmuje jedną podpowiedź z tego leada.
     *
     * Idempotentne: drugie kliknięcie w to samo (podwójny klik, ponowiony request)
     * nie może wywrócić się na indeksie unikalnym pary lead↔zlecenie.
     */
    @Transactional
    fun dismiss(
        studioId: StudioId,
        leadId: UUID,
        visitId: UUID,
        userId: UserId,
        userName: String
    ) {
        leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        if (feedbackRepository.findByLeadIdAndVisitId(leadId, visitId) != null) return

        feedbackRepository.save(
            VisitMatchFeedbackEntity(
                studioId = studioId.value,
                leadId = leadId,
                visitId = visitId,
                verdict = VisitMatchVerdict.IRRELEVANT.name,
                createdBy = userId.value,
                createdByName = userName
            )
        )
    }

    private fun toDto(visit: VisitEntity, tier: MatchTier): SimilarVisitDto {
        val provisional = visit.status == VisitStatus.IN_PROGRESS
        // Kwota LICZONA TAK, JAK LICZY JĄ SAMA WIZYTA. Naiwna suma pozycji wciąga
        // usługi ODRZUCONE przez klienta i te, które dopiero czekają na jego zgodę —
        // czyli pieniądze, których nikt nigdy nie zapłacił. Handlowiec dostaje tę
        // liczbę po to, żeby na niej oprzeć wycenę, więc jedyne dopuszczalne źródło
        // to metoda domeny (Visit.calculateTotalGross), a nie druga, własna reguła.
        val domain = visit.toDomain()
        return SimilarVisitDto(
            visitId = visit.id.toString(),
            visitNumber = visit.visitNumber,
            vehicle = listOfNotNull(
                visit.brandSnapshot.trim().takeIf { it.isNotEmpty() },
                visit.modelSnapshot.trim().takeIf { it.isNotEmpty() },
                visit.yearOfProductionSnapshot?.toString()
            ).joinToString(" "),
            services = domain.serviceItems
                .filter { it.status != VisitServiceStatus.REJECTED }
                .map { it.serviceName }
                .filter { it.isNotBlank() }
                .distinct(),
            totalGross = domain.calculateTotalGross().amountInCents,
            date = visit.actualCompletionDate ?: visit.scheduledDate,
            priceProvisional = provisional,
            matchTier = tier.name
        )
    }

    companion object {
        const val REASON_SERVICE_NOT_IN_CATALOG = "SERVICE_NOT_IN_CATALOG"
        const val REASON_VEHICLE_UNKNOWN = "VEHICLE_UNKNOWN"

        /** Wartość, której żaden brand_key/model_key nie przyjmie — wyłącza gałąź modelu w SQL. */
        private const val NO_MATCH_KEY = " "
    }
}
