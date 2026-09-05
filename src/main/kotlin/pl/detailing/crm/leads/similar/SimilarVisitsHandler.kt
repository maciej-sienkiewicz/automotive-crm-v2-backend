package pl.detailing.crm.leads.similar

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadEntity
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
 * WYNIK JEST LICZONY RAZ I ZAPISYWANY (lead_similar_matches) — w tle, gdy tylko
 * auto leada jest rozstrzygnięte ([LeadSimilarPrecomputeListener]), a leniwie
 * przy pierwszym otwarciu, gdy tła nie było (lead sprzed wdrożenia, awaria).
 * Otwarcie leada CZYTA zapisany dobór; „Sprawdź ponownie" ([refresh]) przelicza
 * na wyraźne życzenie — np. gdy indeks urósł albo cennik się zmienił.
 *
 * Sama inteligencja pracuje jeszcze wcześniej i też zapisuje wyniki:
 *  - rodzina każdej nazwy usługi — raz na nazwę (service_families),
 *  - segment każdego modelu auta — raz na model (vehicle_segments),
 *  - stempel każdego zlecenia — uzgadniaczem (visit_index_state + sygnatury),
 *  - intencja leada — raz na leada (lead_service_intents).
 * Samo przeliczenie doboru to SQL po kolumnach i krata w [SimilarVisitMatcher].
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
    private val matchesRepository: LeadSimilarMatchesRepository,
    private val intentService: LeadServiceIntentService,
    private val segmentService: VehicleSegmentService,
    @Value("\${crm.ai.similar-visits.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.similar-visits.max-results:6}") private val maxResults: Int,
    @Value("\${crm.ai.similar-visits.max-candidates:400}") private val maxCandidates: Int
) {

    @Transactional
    fun findFor(studioId: StudioId, leadId: UUID): SimilarVisitsDto {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val indexed = indexStateRepository.countByStudioId(studioId.value)
        if (!enabled || indexed == 0L) return SimilarVisitsDto(emptyList(), indexed)

        // Zapisany dobór wygrywa; liczymy tylko, gdy go nie ma (lead sprzed wdrożenia
        // albo tło zawiodło) — i wtedy od razu zapisujemy, żeby drugie otwarcie
        // zastało wynik gotowy. Celowo bez backfillu: stare leady płacą raz, leniwie.
        val stored = matchesRepository.findById(leadId).orElse(null)
            ?: compute(lead, forceIntent = false)?.also { matchesRepository.save(it) }
            ?: return SimilarVisitsDto(emptyList(), indexed)

        return hydrate(studioId, leadId, stored, indexed)
    }

    /**
     * „Sprawdź ponownie": przeliczenie na wyraźne życzenie. Intencja idzie do modelu
     * OD NOWA, z pominięciem dziennika — odcisk treści nie widzi zmian CENNIKA,
     * a to właśnie po dopisaniu brakującej usługi ten przycisk ma sens.
     */
    @Transactional
    fun refresh(studioId: StudioId, leadId: UUID): SimilarVisitsDto {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val indexed = indexStateRepository.countByStudioId(studioId.value)
        if (!enabled) return SimilarVisitsDto(emptyList(), indexed)

        val stored = compute(lead, forceIntent = true)?.also { matchesRepository.save(it) }
            ?: return SimilarVisitsDto(emptyList(), indexed)

        return hydrate(studioId, leadId, stored, indexed)
    }

    /** Liczenie w tle po rozstrzygnięciu auta — patrz [LeadSimilarPrecomputeListener]. */
    @Transactional
    fun computeAndStore(studioId: StudioId, leadId: UUID) {
        if (!enabled) return
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value) ?: return
        compute(lead, forceIntent = false)?.let { matchesRepository.save(it) }
    }

    /**
     * Właściwy dobór. Zwraca wiersz do zapisania albo null, gdy wyniku NIE WOLNO
     * utrwalić: odczyt intencji zawiódł, więc zapis zamroziłby chwilową awarię
     * modelu jako wieczne „nie rozpoznaliśmy usługi".
     */
    private fun compute(lead: LeadEntity, forceIntent: Boolean): LeadSimilarMatchesEntity? {
        val brandKey = lead.vehicleBrand?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val modelKey = lead.vehicleModel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val segment = runCatching { segmentService.classify(lead.vehicleBrand, lead.vehicleModel) }
            .getOrNull()?.sizeSegment?.name?.takeIf { it != "UNKNOWN" }

        // Krata zaczyna się od auta: bez modelu i bez segmentu żadna ranga nie
        // istnieje. Pokazanie „czegokolwiek" byłoby udawaniem podobieństwa.
        if ((brandKey == null || modelKey == null) && segment == null) {
            return row(lead, REASON_VEHICLE_UNKNOWN, emptyList())
        }

        val intent = intentService.intentFor(StudioId(lead.studioId), lead.id, lead.initialMessage, forceIntent)
            ?: return null

        // Decyzja właściciela produktu: robota spoza cennika = ŻADNYCH cen.
        // Cena innej roboty podana pewnym głosem jest gorsza niż brak podpowiedzi.
        if (intent.status == ServiceIntentStatus.NOT_IN_CATALOG) {
            return row(lead, REASON_SERVICE_NOT_IN_CATALOG, emptyList())
        }

        val candidates = indexStateRepository.findCandidates(
            studioId = lead.studioId,
            brandKey = brandKey ?: NO_MATCH_KEY,
            modelKey = modelKey ?: NO_MATCH_KEY,
            sizeSegment = segment,
            version = VisitSimilarityIndexer.CURRENT_SIGNATURE_VERSION,
            pageable = PageRequest.of(0, maxCandidates)
        )
        val signatures = signatureRepository.findByVisitIdIn(candidates.map { it.visitId })
            .groupBy { it.visitId }

        val graded = candidates.mapNotNull { candidate ->
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
            // Zapas ponad ekran: zdjęcie podpowiedzi „X-em" dosuwa następną
            // z zapisanych, zamiast skracać listę do czasu ręcznego odświeżenia.
            .take(maxResults * STORE_FACTOR)
            .map { it.first to it.second }

        return row(lead, null, graded)
    }

    /** Zapisany dobór → wiersze na ekran: odsiew zdjętych, przycięcie, kwoty z bazy. */
    private fun hydrate(
        studioId: StudioId,
        leadId: UUID,
        stored: LeadSimilarMatchesEntity,
        indexed: Long
    ): SimilarVisitsDto {
        stored.emptyReason?.let { return SimilarVisitsDto(emptyList(), indexed, emptyReason = it) }

        // Zdjęta podpowiedź nie wraca; odsiew idzie PRZED przycięciem, żeby na
        // zwolnione miejsce weszła następna pozycja z zapasu zamiast luki.
        val dismissed = feedbackRepository.findByLeadId(leadId).map { it.visitId }.toSet()
        val visible = stored.parsed()
            .filterNot { (visitId, _) -> visitId in dismissed }
            .take(maxResults)
        if (visible.isEmpty()) return SimilarVisitsDto(emptyList(), indexed)

        // Druga bariera studia: identyfikatory są z zapisu per lead, ale odczyt
        // wizyt i tak filtruje po studiu — jeden błąd nie może zamienić się
        // w cudze ceny na ekranie.
        val visits = visitRepository
            .findByStudioIdAndIdIn(studioId.value, visible.map { it.first })
            .associateBy { it.id }

        return SimilarVisitsDto(
            items = visible.mapNotNull { (visitId, tier) ->
                visits[visitId]?.let { toDto(it, tier) }
            },
            indexedVisits = indexed
        )
    }

    private fun row(lead: LeadEntity, emptyReason: String?, matches: List<Pair<UUID, MatchTier>>) =
        LeadSimilarMatchesEntity(
            leadId = lead.id,
            studioId = lead.studioId,
            emptyReason = emptyReason,
            matches = LeadSimilarMatchesEntity.serialize(matches),
            computedAt = Instant.now()
        )

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

        /** Ile ekranów zapasu trzyma zapisany dobór — na dosuwanie po zdjęciach „X-em". */
        private const val STORE_FACTOR = 2

        /** Wartość, której żaden brand_key/model_key nie przyjmie — wyłącza gałąź modelu w SQL. */
        private const val NO_MATCH_KEY = " "
    }
}
