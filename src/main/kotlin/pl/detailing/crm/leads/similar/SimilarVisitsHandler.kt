package pl.detailing.crm.leads.similar

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.segment.VehicleSegmentService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Jedno podobne zlecenie, gotowe do pokazania. Wszystkie pola pochodzą z bazy. */
data class SimilarVisitDto(
    val visitId: String,
    val visitNumber: String,
    val vehicle: String,
    val services: List<String>,
    val totalGross: Long,
    /** Data zakończenia, a gdy zlecenie trwa — planowana. */
    val date: Instant,
    val status: String,
    /**
     * Kwota nie jest jeszcze ostateczna: zlecenie w toku dobiera usługi do samego
     * wydania auta. Bez tego oznaczenia liczba na ekranie udawałaby fakt.
     */
    val priceProvisional: Boolean,
    /** SAME_MODEL | SAME_BRAND | SAME_CLASS | ANY — jak blisko trafiliśmy w pojazd. */
    val matchTier: String
)

data class SimilarVisitsDto(
    val items: List<SimilarVisitDto>,
    /** Ile zleceń studio ma w ogóle w indeksie — odróżnia „nic nie pasuje" od „nie ma czego szukać". */
    val indexedVisits: Long
)

/**
 * Odpowiada na pytanie „co robiliśmy dla takiego auta i takiej roboty".
 *
 * Trzy kroki, każdy z inną rolą:
 *  1. ZAPYTANIE — składane z tego, co o leadzie JUŻ wiemy: auto rozpoznane przy
 *     tworzeniu, tagi usług nadane automatem, treść pierwszej wiadomości. Żadnego
 *     nowego wywołania modelu; te dane i tak powstają.
 *  2. DOBÓR — [SimilarVisitFinder]: kaskada po aucie, podobieństwo tekstu wewnątrz kroku.
 *  3. PRZESIEW — [SimilarVisitReranker]: który z kandydatów odpowiada na to samo pytanie.
 *
 * LICZONE NA ŻĄDANIE, nie przy tworzeniu leada. Większości leadów nikt nigdy nie
 * otworzy, a policzenie tego dla każdego byłoby płaceniem za odpowiedź, o którą nikt
 * nie zapytał. Stąd też nie ma tu limitu dziennego, jaki ma klasyfikator leadów:
 * tam automat uruchamiała przychodząca poczta i nikt nie trzymał ręki na wydatku,
 * tutaj każde wywołanie to czyjeś świadome kliknięcie.
 *
 * Pamięć podręczna trzyma SAME IDENTYFIKATORY, nie gotowe wiersze: kwoty i statusy
 * doczytujemy z bazy przy każdym odczycie, więc zlecenie, które w międzyczasie
 * dostało kolejną usługę, nie pokazuje wczorajszej ceny.
 */
@Service
class SimilarVisitsHandler(
    private val leadRepository: LeadRepository,
    private val visitRepository: SimilarVisitReadRepository,
    private val feedbackRepository: VisitMatchFeedbackRepository,
    private val indexStateRepository: VisitIndexStateRepository,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
    private val segmentService: VehicleSegmentService,
    private val finder: SimilarVisitFinder,
    private val reranker: SimilarVisitReranker,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${crm.ai.similar-visits.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.similar-visits.min-confidence:0.5}") private val minConfidence: Double,
    @Value("\${crm.ai.similar-visits.max-results:6}") private val maxResults: Int,
    @Value("\${crm.ai.similar-visits.cache-minutes:60}") private val cacheMinutes: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun findFor(studioId: StudioId, leadId: UUID): SimilarVisitsDto {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val indexed = indexStateRepository.countByStudioId(studioId.value)
        if (!enabled) return SimilarVisitsDto(emptyList(), indexed)

        // Usunięta podpowiedź nie wraca: pokazanie zlecenia drugi raz po tym, jak
        // człowiek je stąd zdjął, jest gorsze niż niepokazanie niczego. Zapis jest
        // per para lead↔zlecenie, więc przy innym leadzie to samo zlecenie wróci.
        val dismissed = feedbackRepository.findByLeadId(leadId).map { it.visitId }.toSet()

        val ranked = cachedRanking(leadId)
            ?: rank(studioId, lead.vehicleBrand, lead.vehicleModel, buildQuery(studioId, leadId, lead.initialMessage))
                .also { cacheRanking(leadId, it) }

        val visible = ranked.filterNot { it.visitId in dismissed }.take(maxResults)
        if (visible.isEmpty()) return SimilarVisitsDto(emptyList(), indexed)

        val visits = visitRepository
            .findByStudioIdAndIdIn(studioId.value, visible.map { it.visitId })
            .associateBy { it.id }

        return SimilarVisitsDto(
            items = visible.mapNotNull { match ->
                visits[match.visitId]?.let { toDto(it, match.tier) }
            },
            indexedVisits = indexed
        )
    }

    /**
     * Zapytanie do wyszukiwarki: treść pierwszej wiadomości uzupełniona o to, co
     * automat już z niej odczytał.
     *
     * Same tagi byłyby za ubogie („PPF_WRAP" nie niesie tego, że chodzi o cały przód),
     * a sama treść bywa jednym zdaniem bez nazwy usługi. Razem opisują robotę tak,
     * jak opisano ją w indeksie.
     */
    private fun buildQuery(studioId: StudioId, leadId: UUID, initialMessage: String?): String {
        val labels = tagCatalog.labelsByCode(studioId)
        val services = tagService.tagsOf(leadId).mapNotNull { labels[it] }
        return listOfNotNull(
            initialMessage?.trim()?.takeIf { it.isNotEmpty() },
            services.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ).joinToString("\n")
    }

    private fun rank(studioId: StudioId, brand: String?, model: String?, query: String): List<RankedMatch> {
        if (query.isBlank()) return emptyList()

        val segment = runCatching { segmentService.classify(brand, model) }.getOrNull()
        val candidates = finder.find(
            studioId = studioId,
            query = query,
            brand = brand,
            model = model,
            sizeSegment = segment?.sizeSegment?.name,
            marketTier = segment?.marketTier?.name
        )
        if (candidates.isEmpty()) return emptyList()

        val verdicts = runBlocking { reranker.rerank(query, candidates) }.associateBy { it.visitId }

        // Awaria modelu nie ma zamieniać sekcji w pustkę: kandydaci są już zawężeni
        // po aucie, więc bez przesiewu lista jest gorsza, ale wciąż na temat.
        // Zapisujemy to w logu, bo cicha zmiana jakości doboru jest nie do wyśledzenia.
        if (verdicts.isEmpty()) {
            log.info("[SIMILAR_VISITS] Brak przesiewu LLM — pokazuję {} kandydatów po samej kaskadzie", candidates.size)
            return candidates.map { RankedMatch(it.visitId, it.tier) }.sortedBy { it.tier.ordinal }
        }

        return candidates
            .mapNotNull { candidate ->
                val verdict = verdicts[candidate.visitId.toString()] ?: return@mapNotNull null
                if (!verdict.comparable || verdict.confidence < minConfidence) {
                    log.debug(
                        "[SIMILAR_VISITS] Odrzucono {} (pewność {}): {}",
                        candidate.visitId, verdict.confidence, verdict.reasoning ?: "-"
                    )
                    return@mapNotNull null
                }
                RankedMatch(candidate.visitId, candidate.tier)
            }
            // Bliskość auta przed pewnością modelu: „mieliśmy dokładnie taką Panamerę"
            // jest mocniejszą odpowiedzią niż pewniejsze dopasowanie na innym aucie.
            .sortedBy { it.tier.ordinal }
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
            status = visit.status.name,
            priceProvisional = provisional,
            matchTier = tier.name
        )
    }

    // ── Pamięć podręczna ─────────────────────────────────────────────────────
    //
    // Trzyma wyłącznie identyfikatory z krokiem kaskady. Ponowne otwarcie leada nie
    // płaci za osadzenie i przesiew, a kwoty i tak doczytują się z bazy — więc wynik
    // nie zestarzeje się razem z ceną.

    private fun cacheKey(leadId: UUID) = "similar-visits:$leadId"

    private fun cachedRanking(leadId: UUID): List<RankedMatch>? =
        runCatching {
            redisTemplate.opsForValue().get(cacheKey(leadId))?.let { raw ->
                if (raw.isEmpty()) return@let emptyList()
                raw.split('|').mapNotNull { entry ->
                    val (id, tier) = entry.split(';').takeIf { it.size == 2 } ?: return@mapNotNull null
                    RankedMatch(UUID.fromString(id), MatchTier.valueOf(tier))
                }
            }
        }.getOrNull()

    private fun cacheRanking(leadId: UUID, ranking: List<RankedMatch>) {
        runCatching {
            redisTemplate.opsForValue().set(
                cacheKey(leadId),
                ranking.joinToString("|") { "${it.visitId};${it.tier.name}" },
                Duration.ofMinutes(cacheMinutes)
            )
        }.onFailure {
            // Redis niedostępny znaczy tylko tyle, że policzymy drugi raz.
            log.debug("[SIMILAR_VISITS] Nie udało się zapisać w pamięci podręcznej: {}", it.message)
        }
    }

    private data class RankedMatch(val visitId: UUID, val tier: MatchTier)
}
