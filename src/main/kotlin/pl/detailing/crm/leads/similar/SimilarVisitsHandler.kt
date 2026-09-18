package pl.detailing.crm.leads.similar

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.attachment.LeadAttachmentRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.similar.feedback.DismissReason
import pl.detailing.crm.leads.similar.pricing.AbstentionPolicy
import pl.detailing.crm.leads.similar.pricing.AnchorGate
import pl.detailing.crm.leads.similar.pricing.AnchorVerdict
import pl.detailing.crm.leads.similar.pricing.AnchorVerifier
import pl.detailing.crm.leads.similar.pricing.CompClass
import pl.detailing.crm.leads.similar.pricing.CompEvaluation
import pl.detailing.crm.leads.similar.pricing.GateThresholds
import pl.detailing.crm.leads.similar.pricing.LeadMatchDecisionEntity
import pl.detailing.crm.leads.similar.pricing.LeadMatchDecisionRepository
import pl.detailing.crm.leads.similar.pricing.PriceBand
import pl.detailing.crm.leads.similar.pricing.VerifierCandidate
import pl.detailing.crm.leads.similar.pricing.VerifierVerdict
import pl.detailing.crm.leads.similar.vision.LeadAttachmentFactsRepository
import pl.detailing.crm.service.taxonomy.ServicePart
import pl.detailing.crm.service.taxonomy.ServiceScope
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.segment.VehicleSegmentService
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Jedno podobne zlecenie, gotowe do pokazania. Wszystkie pola pochodzą z bazy. */
data class SimilarVisitDto(
    val visitId: String,
    val visitNumber: String,
    val vehicle: String,
    val services: List<String>,
    /**
     * PEŁNA kwota zlecenia, liczona tak, jak liczy ją sama wizyta — bez pozycji
     * odrzuconych i czekających na zgodę klienta.
     */
    val totalGross: Long,
    /** Data zakończenia, a gdy zlecenie trwa — planowana. */
    val date: Instant,
    /** Kwota nie jest jeszcze ostateczna: zlecenie w toku dobiera usługi do wydania auta. */
    val priceProvisional: Boolean,
    /** Ranga z [MatchTier] — auto × usługa; pusty łańcuch dla wpisu historii auta. */
    val matchTier: String,
    /** DIRECT | ADJUSTED — klasa kotwicy po bramkach i weryfikatorze. */
    val compClass: String? = null,
    /** Zdanie weryfikatora: dlaczego ta kwota jest uczciwym odniesieniem. */
    val whyItFits: String? = null,
    /** Zdanie weryfikatora: co jawnie różni tę realizację od zapytania. */
    val whatDiffers: String? = null,
    /** Miniatura pierwszego zdjęcia realizacji (presigned URL) — dowód rzemiosła. */
    val photoThumbnailUrl: String? = null,
    /** Zlecenie z pasma 12–24 mies. — interfejs dokleja rok przy dacie. */
    val agedLabel: Boolean = false
)

/** Przedział cenowy z realizacji — nagłówek sekcji, gdy verdict = BAND. */
data class PriceBandDto(
    val minGross: Long,
    val medianGross: Long,
    val maxGross: Long,
    val sampleSize: Int,
    /** Wszystkie realizacje po tej samej stronie kotwicy — pokaż etykietę, nie przedział. */
    val oneSided: Boolean,
    /** Kotwica katalogowa, w groszach — do zdania „katalog: X, historia: Y–Z". */
    val anchorGross: Long?,
    /** (mediana − kotwica) / kotwica, np. -0.08 = historia o 8% niżej niż katalog. */
    val divergence: Double?
)

data class SimilarVisitsDto(
    val items: List<SimilarVisitDto>,
    /** Ile zleceń studio ma w ogóle w indeksie — odróżnia „nic nie pasuje" od „nie ma czego szukać". */
    val indexedVisits: Long,
    /**
     * Powód pustki, gdy nie wynika ona z ubóstwa historii:
     * SERVICE_NOT_IN_CATALOG — robota spoza cennika (nie podpowiadamy cen),
     * VEHICLE_UNKNOWN — lead nie ma rozpoznanego auta,
     * NEEDS_INSPECTION — roboty nie da się wycenić zdalnie (np. zapytanie odsyła
     *   do zdjęć, których nie umiemy odczytać).
     * Null, gdy lista nie jest pusta albo pustka znaczy po prostu „brak trafień".
     */
    val emptyReason: String? = null,
    /** BAND | SINGLE | EVIDENCE_ONLY | ABSTAIN — co sekcja twierdzi o cenie. */
    val verdict: String? = null,
    /** Kod nazwanego milczenia przy ABSTAIN — patrz [AbstentionPolicy]. */
    val abstentionCode: String? = null,
    /** Przedział z realizacji, gdy verdict = BAND. */
    val band: PriceBandDto? = null,
    /**
     * Historia DOKŁADNIE tego modelu — osobna sekcja, jawnie BEZ roli cenowej.
     * Wypełniana, gdy intencji nie dało się odczytać (dawny MODEL_HISTORY) —
     * dowód, że znamy to auto, a nie sugestia kwoty.
     */
    val vehicleHistory: List<SimilarVisitDto> = emptyList()
)

/**
 * Odpowiada na pytanie „ile bierzemy za taką robotę i czym to udowodnić".
 *
 * WYNIK JEST LICZONY RAZ I ZAPISYWANY (lead_similar_matches) — w tle, gdy tylko
 * auto leada jest rozstrzygnięte ([LeadSimilarPrecomputeListener]), a leniwie przy
 * pierwszym otwarciu, gdy tła nie było. Otwarcie leada CZYTA zapisany dobór;
 * „Sprawdź ponownie" ([refresh]) przelicza na wyraźne życzenie.
 *
 * Wiersz z [LeadSimilarMatchesEntity.rulesVersion] niższym niż bieżąca stała jest
 * przeliczany przy najbliższym otwarciu — bez tego żadna zmiana bramek nie
 * dotarłaby do leada, który już ma zapisany wynik.
 *
 * Potok na jednego leada (szczegóły: docs/similar-visits-redesign.md §4):
 *   intencja/potrzeba (L3, dziennikowana) → SQL z filtrem wartościowym w WHERE →
 *   AnchorGate (czysta funkcja: osie, krata, skala, skupienie, wiek) →
 *   AnchorVerifier (L4, adaptacyjnie) → PriceBand + AbstentionPolicy (czyste funkcje)
 *   → zapis wyniku + dziennik decyzji per KANDYDAT (także odrzucony).
 *
 * Właściciel przy otwarciu leada NIGDY nie czeka na model.
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
    private val decisionRepository: LeadMatchDecisionRepository,
    private val verifier: AnchorVerifier,
    private val photoSessionService: PhotoSessionService? = null,
    private val attachmentRepository: LeadAttachmentRepository? = null,
    private val visionFactsRepository: LeadAttachmentFactsRepository? = null,
    private val intentRepository: LeadServiceIntentRepository? = null,
    private val suggestionService: LeadServiceSuggestionService? = null,
    @Value("\${crm.ai.similar-visits.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.similar-visits.max-results:6}") private val maxResults: Int,
    @Value("\${crm.ai.similar-visits.max-candidates:400}") private val maxCandidates: Int,
    private val thresholds: GateThresholds = GateThresholds()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun findFor(studioId: StudioId, leadId: UUID): SimilarVisitsDto {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val indexed = indexStateRepository.countByStudioId(studioId.value)
        if (!enabled || indexed == 0L) return SimilarVisitsDto(emptyList(), indexed)

        // Zapisany dobór wygrywa TYLKO wtedy, gdy policzyły go bieżące reguły.
        // Wiersz ze starszą rules_version jest przeliczany — to jedyna droga,
        // którą naprawa bramek dociera do już otwieranych leadów.
        val stored = matchesRepository.findById(leadId).orElse(null)
            ?.takeIf { it.rulesVersion >= LeadSimilarMatchesEntity.CURRENT_RULES_VERSION }
            ?: compute(lead, forceIntent = false)?.also {
                matchesRepository.save(it)
                recomputeSuggestionsQuietly(studioId, leadId)
            }
            ?: return SimilarVisitsDto(emptyList(), indexed)

        return hydrate(studioId, lead, stored, indexed)
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

        return hydrate(studioId, lead, stored, indexed)
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
     * utrwalić: odczyt intencji albo weryfikator zawiódł, więc zapis zamroziłby
     * chwilową awarię modelu jako wieczny werdykt.
     */
    private fun compute(lead: LeadEntity, forceIntent: Boolean): LeadSimilarMatchesEntity? {
        val brandKey = lead.vehicleBrand?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val modelKey = lead.vehicleModel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val segment = runCatching { segmentService.classify(lead.vehicleBrand, lead.vehicleModel) }
            .getOrNull()?.sizeSegment?.name?.takeIf { it != "UNKNOWN" }

        // Krata zaczyna się od auta: bez modelu i bez segmentu żadna ranga nie
        // istnieje. Pokazanie „czegokolwiek" byłoby udawaniem podobieństwa.
        if ((brandKey == null || modelKey == null) && segment == null) {
            return abstainRow(lead, REASON_VEHICLE_UNKNOWN, AbstentionPolicy.CODE_NO_VEHICLE)
        }

        val intent = intentService.intentFor(
            StudioId(lead.studioId), lead.id, lead.initialMessage, forceIntent, lead.threadId
        ) ?: return null

        when (intent.status) {
            // Decyzja właściciela produktu: robota spoza cennika = ŻADNYCH cen.
            // CATALOG_NEAR_MISS („macie X, klient pyta o Y") również: inna część
            // auta to inna robota, więc żaden kandydat nie ma prawa podać kwoty.
            ServiceIntentStatus.NOT_IN_CATALOG, ServiceIntentStatus.CATALOG_NEAR_MISS ->
                return abstainRow(lead, REASON_SERVICE_NOT_IN_CATALOG, AbstentionPolicy.CODE_NOT_IN_CATALOG)

            ServiceIntentStatus.NEEDS_INSPECTION ->
                return abstainRow(lead, REASON_NEEDS_INSPECTION, AbstentionPolicy.CODE_NEEDS_INSPECTION)

            // Intencji nie znamy — sekcja cenowa milczy, a historię DOKŁADNIE tego
            // modelu (bez roli cenowej) dokłada hydratacja.
            ServiceIntentStatus.NO_SERVICE ->
                return abstainRow(lead, null, null)

            ServiceIntentStatus.MATCHED -> Unit
        }

        // Reguła UNDERSPECIFIED: klient odsyła do zdjęć, których nie umiemy odczytać,
        // a tekst nie niesie skali roboty — wycena zdalna byłaby zgadywaniem.
        if (underspecified(lead, intent)) {
            return abstainRow(lead, REASON_NEEDS_INSPECTION, AbstentionPolicy.CODE_NEEDS_INSPECTION)
        }

        val now = Instant.now()
        val anchor = intent.anchorGross
        // Filtr wartościowy W ZAPYTANIU (dolna granica pasma): bez niego limit
        // kandydatów wycina okno czasowe zamiast okna trafności. 0 = filtr wyłączony
        // (brak kotwicy nie może skasować wszystkich kandydatów).
        val minTotalGross = anchor?.let { (it * thresholds.minRatioFor(it)).toLong() } ?: 0L
        val notOlderThan = now.minus(thresholds.maxAgeMonths * 30, ChronoUnit.DAYS)

        val candidates = indexStateRepository.findCandidates(
            studioId = lead.studioId,
            brandKey = brandKey ?: NO_MATCH_KEY,
            modelKey = modelKey ?: NO_MATCH_KEY,
            sizeSegment = segment,
            version = MIN_ACCEPTED_SIGNATURE_VERSION,
            minTotalGross = minTotalGross,
            notOlderThan = notOlderThan,
            pageable = PageRequest.of(0, maxCandidates)
        )
        val signatures = signatureRepository.findByVisitIdIn(candidates.map { it.visitId })
            .groupBy { it.visitId }

        val evaluations = candidates.map { candidate ->
            AnchorGate.evaluate(
                candidate = candidate,
                signatures = signatures[candidate.visitId].orEmpty(),
                intent = intent,
                leadBrandKey = brandKey,
                leadModelKey = modelKey,
                leadSegment = segment,
                now = now,
                thresholds = thresholds
            )
        }

        // Ranga przed świeżością: bliższe dopasowanie bije nowsze zlecenie.
        var accepted = evaluations
            .filter { it.compClass != CompClass.REJECTED }
            .sortedWith(
                compareBy<CompEvaluation> { it.tier?.ordinal ?: Int.MAX_VALUE }
                    .thenByDescending { it.happenedAt ?: Instant.EPOCH }
            )

        // ── Weryfikator (L4) — adaptacyjnie ─────────────────────────────────────
        // Tylko gdy jest co rozsądzać: ≥2 finalistów o rozrzucie cen ponad próg.
        // Awaria weryfikatora = NIC nie utrwalamy (abstencja przejściowa): fałszywa
        // kotwica kosztuje więcej niż brakująca, a zapis zamroziłby awarię na zawsze.
        val verifierPool = accepted.take(VERIFIER_MAX_CANDIDATES)
        val verdicts: Map<UUID, VerifierVerdict>
        if (shouldVerify(verifierPool)) {
            val byPosition = verifierPool.mapIndexed { index, eval -> (index + 1) to eval }.toMap()
            val answers = verifier.verify(
                needSummary(lead, intent),
                byPosition.map { (position, eval) ->
                    VerifierCandidate(
                        candidateId = position,
                        vehicle = vehicleLabel(eval.visitId, candidates),
                        axes = dominantAxes(signatures[eval.visitId].orEmpty()),
                        serviceNames = matchingServiceNames(signatures[eval.visitId].orEmpty(), intent),
                        referenceGross = eval.referenceGross ?: 0,
                        happened = eval.happenedAt?.let { HAPPENED_FORMAT.format(it.atZone(ZoneOffset.UTC)) }
                    )
                }
            ) ?: run {
                log.warn("[SIMILAR_VISITS] Weryfikator niedostępny — dobór dla leada {} bez zapisu", lead.id)
                return null
            }
            verdicts = byPosition.mapNotNull { (position, eval) ->
                answers[position]?.let { eval.visitId to it }
            }.toMap()
            // Kandydat oceniony negatywnie ALBO pominięty w odpowiedzi wypada:
            // bez oceny nie pokazujemy — asymetria straty działa też na braki.
            val verifiedPool = verifierPool.filter { verdicts[it.visitId]?.passes == true }
            accepted = verifiedPool + accepted.drop(verifierPool.size)
        } else {
            verdicts = emptyMap()
        }

        val toStore = accepted.take(maxResults * STORE_FACTOR)
        val shown = toStore.take(maxResults)

        val band = PriceBand.of(shown.mapNotNull { it.referenceGross }, anchor)
        val outcome = AbstentionPolicy.decide(shown.size, band, thresholds)

        journal(lead, evaluations, shown, verdicts, intent)

        return LeadSimilarMatchesEntity(
            leadId = lead.id,
            studioId = lead.studioId,
            emptyReason = null,
            matches = LeadSimilarMatchesEntity.serializeMatches(
                toStore.map {
                    LeadSimilarMatchesEntity.StoredMatch(
                        it.visitId, it.tier, downgradedClass(it, verdicts[it.visitId]).name
                    )
                }
            ),
            computedAt = Instant.now(),
            verdict = outcome.verdict.name,
            abstentionCode = outcome.abstentionCode,
            bandMin = band?.min,
            bandMedian = band?.median,
            bandMax = band?.max,
            sampleSize = band?.sampleSize,
            oneSided = band?.oneSided ?: false,
            anchorGross = anchor,
            divergence = band?.divergence?.let { BigDecimal(it).setScale(3, RoundingMode.HALF_UP) }
        )
    }

    /**
     * Dziennik decyzji: wiersz per KANDYDAT, także odrzucony. To jedyny sposób,
     * żeby na pytanie „dlaczego próg bagażnika wszedł, a full body nie" odpowiadało
     * zapytanie SQL, a nie śledztwo w kodzie.
     */
    private fun journal(
        lead: LeadEntity,
        evaluations: List<CompEvaluation>,
        shown: List<CompEvaluation>,
        verdicts: Map<UUID, VerifierVerdict>,
        intent: LeadServiceIntent
    ) {
        val positions = shown.mapIndexed { index, eval -> eval.visitId to index }.toMap()
        decisionRepository.deleteByLeadId(lead.id)
        decisionRepository.saveAll(
            evaluations.map { eval ->
                val verdict = verdicts[eval.visitId]
                LeadMatchDecisionEntity(
                    studioId = lead.studioId,
                    leadId = lead.id,
                    visitId = eval.visitId,
                    tier = eval.tier?.name,
                    compClass = downgradedClass(eval, verdict).name,
                    valueCoverage = eval.valueCoverage?.toScaled(3),
                    valueFocus = eval.valueFocus?.toScaled(3),
                    priceRatio = eval.priceRatio?.toScaled(4),
                    shown = eval.visitId in positions,
                    position = positions[eval.visitId],
                    rejectCode = when {
                        eval.rejectCode != null -> eval.rejectCode
                        verdict != null && !verdict.passes -> CompEvaluation.REJECT_VERIFIER
                        else -> null
                    },
                    promptVersion = LeadServiceIntentService.PROMPT_VERSION,
                    rulesVersion = LeadSimilarMatchesEntity.CURRENT_RULES_VERSION,
                    verifierSameOperation = verdict?.sameOperation,
                    verifierSamePart = verdict?.samePart,
                    verifierSameScale = verdict?.sameScale,
                    verifierPriceComparable = verdict?.priceComparable,
                    verifierAgreedWithGate = verdict?.let { it.passes == (eval.compClass != CompClass.REJECTED) },
                    whyItFits = verdict?.whyItFits,
                    whatDiffers = verdict?.whatDiffers
                )
            }
        )
    }

    /** Zapisany dobór → wiersze na ekran: odsiew zdjętych, przycięcie, kwoty z bazy. */
    private fun hydrate(
        studioId: StudioId,
        lead: LeadEntity,
        stored: LeadSimilarMatchesEntity,
        indexed: Long
    ): SimilarVisitsDto {
        stored.emptyReason?.let { reason ->
            return SimilarVisitsDto(
                emptyList(), indexed,
                emptyReason = reason,
                verdict = AnchorVerdict.ABSTAIN.name,
                abstentionCode = stored.abstentionCode
            )
        }

        // Intencji nie było (dawny MODEL_HISTORY): sekcja cenowa milczy, historia
        // DOKŁADNIE tego modelu idzie osobno, jawnie bez roli cenowej.
        if (stored.verdict == AnchorVerdict.ABSTAIN.name && stored.abstentionCode == null) {
            return SimilarVisitsDto(
                emptyList(), indexed,
                verdict = AnchorVerdict.ABSTAIN.name,
                vehicleHistory = vehicleHistory(lead)
            )
        }

        // Zdjęta podpowiedź nie wraca; wykluczenia o zasięgu STUDIA (z aktywnym TTL)
        // działają na każdym leadzie. Odsiew idzie PRZED przycięciem, żeby na
        // zwolnione miejsce weszła następna pozycja z zapasu zamiast luki.
        val now = Instant.now()
        val dismissed = feedbackRepository.findByLeadId(lead.id).map { it.visitId }.toSet()
        val studioWide = feedbackRepository
            .findByStudioIdAndScope(studioId.value, VisitMatchFeedbackEntity.SCOPE_STUDIO)
            .filter { it.expiresAt == null || it.expiresAt!!.isAfter(now) }
            .map { it.visitId }
            .toSet()
        val candidates = stored.parsedMatches()
            .filterNot { it.visitId in dismissed || it.visitId in studioWide }
        if (candidates.isEmpty()) {
            return SimilarVisitsDto(
                emptyList(), indexed,
                verdict = stored.verdict, abstentionCode = stored.abstentionCode,
                band = bandDto(stored)
            )
        }

        // Druga bariera studia: identyfikatory są z zapisu per lead, ale odczyt
        // wizyt i tak filtruje po studiu — jeden błąd nie może zamienić się
        // w cudze ceny na ekranie.
        val visits = visitRepository
            .findByStudioIdAndIdIn(studioId.value, candidates.map { it.visitId })
            .associateBy { it.id }
        val decisions = decisionRepository.findByLeadIdOrderByCreatedAtDesc(lead.id)
            .groupBy { it.visitId }
            .mapValues { (_, rows) -> rows.first() }

        val items = candidates
            .mapNotNull { match ->
                visits[match.visitId]?.let { toDto(it, match, decisions[match.visitId]) }
            }
            // Zlecenie bez kwoty nie odpowiada na pytanie, po które ktoś tu przyszedł.
            .filter { it.totalGross > 0 }
            .take(maxResults)

        return SimilarVisitsDto(
            items = items,
            indexedVisits = indexed,
            verdict = stored.verdict,
            abstentionCode = stored.abstentionCode,
            band = bandDto(stored)
        )
    }

    private fun abstainRow(lead: LeadEntity, emptyReason: String?, abstentionCode: String?) =
        LeadSimilarMatchesEntity(
            leadId = lead.id,
            studioId = lead.studioId,
            emptyReason = emptyReason,
            matches = "",
            computedAt = Instant.now(),
            verdict = AnchorVerdict.ABSTAIN.name,
            abstentionCode = abstentionCode
        )

    /**
     * Zdejmuje jedną podpowiedź — teraz z POWODEM i ZAKRESEM.
     *
     * Idempotentne po parze lead↔zlecenie. Snapshot osi robi się W CHWILI odrzucenia:
     * późniejsze przestemplowanie wizyty nie może zmienić znaczenia zapisanej opinii.
     * scope=STUDIO wyklucza wizytę z puli compów całego studia do [STUDIO_SCOPE_TTL_DAYS] dni.
     */
    @Transactional
    fun dismiss(
        studioId: StudioId,
        leadId: UUID,
        visitId: UUID,
        userId: UserId,
        userName: String,
        reason: DismissReason = DismissReason.OTHER,
        scope: String = VisitMatchFeedbackEntity.SCOPE_LEAD
    ) {
        leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        if (feedbackRepository.findByLeadIdAndVisitId(leadId, visitId) != null) return

        val need = intentRepository?.findById(leadId)?.orElse(null)
            ?.needs?.split('|')?.firstNotNullOfOrNull { WorkNeed.parse(it) }
        val candidateSignatures = signatureRepository.findByVisitIdIn(listOf(visitId))
        val decision = decisionRepository.findByLeadIdOrderByCreatedAtDesc(leadId)
            .firstOrNull { it.visitId == visitId }
        val effectiveScope = if (scope == VisitMatchFeedbackEntity.SCOPE_STUDIO) scope
        else VisitMatchFeedbackEntity.SCOPE_LEAD

        feedbackRepository.save(
            VisitMatchFeedbackEntity(
                studioId = studioId.value,
                leadId = leadId,
                visitId = visitId,
                verdict = VisitMatchVerdict.IRRELEVANT.name,
                createdBy = userId.value,
                createdByName = userName,
                reasonCode = reason.name,
                scope = effectiveScope,
                expiresAt = if (effectiveScope == VisitMatchFeedbackEntity.SCOPE_STUDIO) {
                    Instant.now().plus(STUDIO_SCOPE_TTL_DAYS, ChronoUnit.DAYS)
                } else null,
                needOperation = need?.operation?.name,
                needPart = need?.part?.name,
                candOperation = dominantOperation(candidateSignatures),
                candPart = dominantPart(candidateSignatures),
                priceRatio = decision?.priceRatio
            )
        )
    }

    // ── Pomocnicze ──────────────────────────────────────────────────────────────

    private fun underspecified(lead: LeadEntity, intent: LeadServiceIntent): Boolean {
        if (intent.scope != ServiceScope.UNKNOWN) return false
        if (intent.needs.any { it.part != ServicePart.UNKNOWN || it.scope != ServiceScope.UNKNOWN }) return false
        val hasImages = attachmentRepository
            ?.findByLeadIdOrderByReceivedAtAsc(lead.id)
            ?.any { it.contentType.lowercase().startsWith("image/") }
            ?: false
        if (!hasImages) return false
        val readableFacts = visionFactsRepository
            ?.findByLeadId(lead.id)
            ?.any { it.readable }
            ?: false
        return !readableFacts
    }

    private fun shouldVerify(pool: List<CompEvaluation>): Boolean {
        if (pool.size < 2) return false
        val amounts = pool.mapNotNull { it.referenceGross }.filter { it > 0 }
        if (amounts.size < 2) return false
        val min = amounts.min().toDouble()
        val max = amounts.max().toDouble()
        return min > 0 && max / min > thresholds.verifierSpreadTrigger
    }

    private fun downgradedClass(eval: CompEvaluation, verdict: VerifierVerdict?): CompClass = when {
        eval.compClass == CompClass.REJECTED -> CompClass.REJECTED
        verdict == null -> eval.compClass
        !verdict.passes -> CompClass.REJECTED
        // Weryfikator potwierdził porównywalność, ale nie pełną tożsamość — degradacja.
        eval.compClass == CompClass.DIRECT &&
            !(verdict.sameOperation && verdict.samePart && verdict.sameScale) -> CompClass.ADJUSTED

        else -> eval.compClass
    }

    private fun needSummary(lead: LeadEntity, intent: LeadServiceIntent): String {
        val car = listOfNotNull(lead.vehicleBrand, lead.vehicleModel).joinToString(" ").ifBlank { "auto nieznane" }
        val needs = intent.needs
            .joinToString("; ") { "${it.operation.name}/${it.part.name}/${it.scope.name}" }
            .ifBlank { "osie nieznane" }
        val positions = intent.matchedNameKeys.joinToString(", ").ifBlank { "brak wskazanych pozycji" }
        val anchor = intent.anchorGross?.let { "${it / 100} zł (${intent.anchorSource ?: "?"})" } ?: "brak"
        return "Auto: $car\nRoboty: $needs\nPozycje cennika: $positions\nKotwica: $anchor"
    }

    private fun vehicleLabel(visitId: UUID, candidates: List<VisitIndexStateEntity>): String {
        val row = candidates.firstOrNull { it.visitId == visitId } ?: return "?"
        return listOfNotNull(row.brandKey, row.modelKey, row.sizeSegment?.let { "($it)" })
            .joinToString(" ")
    }

    private fun dominantAxes(signatures: List<VisitServiceSignatureEntity>): String {
        val operation = dominantOperation(signatures) ?: "UNKNOWN"
        val part = dominantPart(signatures) ?: "UNKNOWN"
        return "$operation/$part"
    }

    private fun dominantOperation(signatures: List<VisitServiceSignatureEntity>): String? =
        signatures.map { it.operation }.filter { it != "UNKNOWN" }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun dominantPart(signatures: List<VisitServiceSignatureEntity>): String? =
        signatures.map { it.part }.filter { it != "UNKNOWN" }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Nazwy pozycji odpowiadających zapytaniu — to na nich weryfikator łapie błędy osi. */
    private fun matchingServiceNames(
        signatures: List<VisitServiceSignatureEntity>,
        intent: LeadServiceIntent
    ): List<String> {
        val families = intent.families.map { it.name }.toSet()
        val matching = signatures.filter {
            it.nameKey in intent.matchedNameKeys || it.family in families
        }
        return (matching.ifEmpty { signatures }).map { it.nameKey }.distinct().take(5)
    }

    private fun vehicleHistory(lead: LeadEntity): List<SimilarVisitDto> {
        val brandKey = lead.vehicleBrand?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val modelKey = lead.vehicleModel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val rows = indexStateRepository.findCandidates(
            studioId = lead.studioId,
            brandKey = brandKey,
            modelKey = modelKey,
            sizeSegment = null,
            version = MIN_ACCEPTED_SIGNATURE_VERSION,
            minTotalGross = 0,
            notOlderThan = Instant.EPOCH,
            pageable = PageRequest.of(0, maxResults)
        )
        val visits = visitRepository
            .findByStudioIdAndIdIn(lead.studioId, rows.map { it.visitId })
            .associateBy { it.id }
        return rows.mapNotNull { row ->
            visits[row.visitId]?.let {
                toDto(it, LeadSimilarMatchesEntity.StoredMatch(row.visitId, null, null), decision = null)
            }
        }
    }

    private fun bandDto(stored: LeadSimilarMatchesEntity): PriceBandDto? {
        val min = stored.bandMin ?: return null
        val median = stored.bandMedian ?: return null
        val max = stored.bandMax ?: return null
        return PriceBandDto(
            minGross = min,
            medianGross = median,
            maxGross = max,
            sampleSize = stored.sampleSize ?: 0,
            oneSided = stored.oneSided,
            anchorGross = stored.anchorGross,
            divergence = stored.divergence?.toDouble()
        )
    }

    private fun toDto(
        visit: VisitEntity,
        match: LeadSimilarMatchesEntity.StoredMatch,
        decision: LeadMatchDecisionEntity?
    ): SimilarVisitDto {
        val provisional = visit.status == VisitStatus.IN_PROGRESS
        // Kwota LICZONA TAK, JAK LICZY JĄ SAMA WIZYTA — jedyne dopuszczalne źródło
        // to metoda domeny (Visit.calculateTotalGross), a nie druga, własna reguła.
        val domain = visit.toDomain()
        val happened = visit.actualCompletionDate ?: visit.scheduledDate
        val ageMonths = ChronoUnit.DAYS.between(happened, Instant.now()) / 30
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
            date = happened,
            priceProvisional = provisional,
            matchTier = match.tier?.name.orEmpty(),
            compClass = match.compClass,
            whyItFits = decision?.whyItFits,
            whatDiffers = decision?.whatDiffers,
            photoThumbnailUrl = thumbnailUrl(visit),
            agedLabel = ageMonths > thresholds.softAgeMonths
        )
    }

    /**
     * Miniatura pierwszego zdjęcia realizacji — dowód rzemiosła obok kwoty.
     * Awaria S3 nie ma prawa wywrócić sekcji: brak zdjęcia to brak zdjęcia.
     */
    private fun thumbnailUrl(visit: VisitEntity): String? = runCatching {
        val photo = visit.photos.minByOrNull { it.uploadedAt } ?: return null
        photoSessionService?.generateDownloadUrl(photo.thumbnailFileId ?: photo.fileId)
    }.getOrNull()

    private fun Double.toScaled(scale: Int): BigDecimal =
        BigDecimal(this).setScale(scale, RoundingMode.HALF_UP)

    companion object {
        const val REASON_SERVICE_NOT_IN_CATALOG = "SERVICE_NOT_IN_CATALOG"
        const val REASON_VEHICLE_UNKNOWN = "VEHICLE_UNKNOWN"
        const val REASON_NEEDS_INSPECTION = "NEEDS_INSPECTION"

        /** Ile ekranów zapasu trzyma zapisany dobór — na dosuwanie po zdjęciach „X-em". */
        private const val STORE_FACTOR = 2

        /** Wartość, której żaden brand_key/model_key nie przyjmie — wyłącza gałąź modelu w SQL. */
        private const val NO_MATCH_KEY = " "

        /**
         * Okno przestemplowania: wiersze wersji 1 (sprzed osi i pieniędzy) PRZECHODZĄ —
         * bramki, którym brakuje danych, same się pomijają (brak danych ≠ zero),
         * więc sekcja w oknie degraduje się do starego zachowania zamiast gasnąć.
         */
        private const val MIN_ACCEPTED_SIGNATURE_VERSION = 1

        /** Górny limit kandydatów podawanych weryfikatorowi — model wchodzi na ≤6 linijek. */
        private const val VERIFIER_MAX_CANDIDATES = 6

        /** TTL wykluczenia o zasięgu studia — pół roku. */
        private const val STUDIO_SCOPE_TTL_DAYS = 180L

        private val HAPPENED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")
    }

    private fun recomputeSuggestionsQuietly(studioId: StudioId, leadId: UUID) {
        runCatching { suggestionService?.recompute(studioId, leadId, force = false) }
            .onFailure { log.warn("[SIMILAR_VISITS] Odświeżenie sugestii dla leada {} nie powiodło się: {}", leadId, it.message) }
    }
}
