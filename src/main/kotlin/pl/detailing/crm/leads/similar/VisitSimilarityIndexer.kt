package pl.detailing.crm.leads.similar

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.service.taxonomy.ClassifiedServiceName
import pl.detailing.crm.service.taxonomy.ServiceFamilyClassifier
import pl.detailing.crm.service.taxonomy.serviceNameKey
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.segment.VehicleSegmentService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Buduje treść stempla zlecenia: auto + wykonane usługi.
 *
 * Czego w stemplu NIE MA i dlaczego:
 *  - KWOTY — cena nie czyni dwóch zleceń podobnymi; wraca z bazy przy odczycie.
 *  - DANYCH KLIENTA — nazwisko i tablice nie mają wpływu na podobieństwo roboty.
 *  - POZYCJI ODRZUCONYCH — usługa, której klient nie chciał, nie opisuje roboty,
 *    którą wykonaliśmy.
 *  - NOTATEK TECHNICZNYCH — opisują stan egzemplarza, nie rodzaj pracy, i zmieniają
 *    się w trakcie wizyty, co kazałoby stemplować od nowa bez zysku.
 */
object VisitDocumentFactory {

    /** Nazwy usług liczone do zlecenia: bez odrzuconych, bez pustych, bez powtórzeń. */
    fun serviceNames(visit: VisitEntity): List<String> =
        visit.serviceItems
            .filter { it.status != VisitServiceStatus.REJECTED }
            .map { it.serviceName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun describe(visit: VisitEntity, sizeSegment: String?, marketTier: String?): String {
        val car = listOfNotNull(
            visit.brandSnapshot.trim().takeIf { it.isNotEmpty() },
            visit.modelSnapshot.trim().takeIf { it.isNotEmpty() },
            visit.yearOfProductionSnapshot?.toString()
        ).joinToString(" ")

        val classification = listOfNotNull(sizeSegment, marketTier)
            .filter { it.isNotBlank() && it != "UNKNOWN" }
            .joinToString(", ")

        val services = serviceNames(visit).joinToString(", ")

        return buildString {
            append(car.ifBlank { "Pojazd nieokreślony" })
            if (classification.isNotBlank()) append(" ($classification)")
            append(": ")
            append(services.ifBlank { "brak wykazanych usług" })
        }
    }

    /** Skrót treści — po nim uzgadniacz poznaje, że stempel wyszedłby identyczny. */
    fun fingerprint(description: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(description.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(64)
}

/**
 * Utrzymuje indeks wyszukiwania zleceń w zgodzie z tabelą wizyt.
 *
 * UZGADNIACZ, A NIE HACZYKI W HANDLERACH. Indeks jest modelem odczytowym, a wizyta
 * zmienia się w kilkunastu miejscach: przyjęcie auta, zapis usług, zatwierdzenie
 * dodatkowej roboty, wydanie, archiwizacja. Wplecenie indeksowania w każde z nich
 * znaczyłoby, że pierwsza nowa ścieżka zapisu, którą ktoś kiedyś dopisze, po cichu
 * wypadnie z indeksu — a brak w indeksie nie rzuca błędem, tylko powoduje, że
 * zlecenia po prostu nie widać. Jedno zadanie chodzące po `visits.updated_at`
 * obsługuje wszystkie ścieżki naraz, także te jeszcze nienapisane, i przy okazji
 * jest całym mechanizmem pierwszego zapełnienia indeksu.
 *
 * Dwa wywołania modelu na NOWY materiał, zero na znany:
 *  - segment auta — raz na model auta, globalny cache (vehicle_segments),
 *  - rodzina usługi — raz na nazwę, globalny cache (service_families).
 * Studio z 5000 wizyt na 40 nazwach usług i 300 modelach aut płaci ~340 klasyfikacji
 * ŁĄCZNIE, nie 5000.
 */
@Service
class VisitSimilarityIndexer(
    private val candidateRepository: VisitIndexCandidateRepository,
    private val stateRepository: VisitIndexStateRepository,
    private val signatureRepository: VisitServiceSignatureRepository,
    private val segmentService: VehicleSegmentService,
    private val familyClassifier: ServiceFamilyClassifier,
    @Value("\${crm.ai.similar-visits.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.similar-visits.reconcile-batch:200}") private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Jedna porcja zaległości. Wielkość porcji jest sufitem kosztu na przebieg:
     * studio wchodzące z pięcioma tysiącami wizyt zapełni indeks w kilkanaście
     * przebiegów zamiast w jednym uderzeniu w API.
     */
    @Scheduled(fixedDelayString = "\${crm.ai.similar-visits.reconcile-interval-ms:300000}")
    fun reconcile() {
        if (!enabled) return
        runCatching { indexPending(batchSize) }
            .onFailure { log.warn("[SIMILAR_VISITS] Uzgadnianie indeksu nie powiodło się: {}", it.message) }
    }

    /** @return ile zleceń zostało ostemplowanych w tym przebiegu. */
    @Transactional
    fun indexPending(limit: Int): Int {
        val candidates = candidateRepository.findPending(
            INDEXABLE_STATUSES, CURRENT_SIGNATURE_VERSION, PageRequest.of(0, limit)
        )
        if (candidates.isEmpty()) return 0

        val known = stateRepository.findByVisitIdIn(candidates.map { it.id }).associateBy { it.visitId }

        // Nazwy z całej porcji klasyfikowane jednym rzutem, per studio: powtórzenia
        // między wizytami są regułą, nie wyjątkiem, a cache trzyma resztę.
        val familiesByStudio: Map<UUID, Map<String, ClassifiedServiceName>> = candidates
            .groupBy { it.studioId }
            .mapValues { (studioId, visits) ->
                runCatching {
                    familyClassifier.classify(studioId, visits.flatMap { VisitDocumentFactory.serviceNames(it) })
                }.getOrElse {
                    log.warn("[SIMILAR_VISITS] Klasyfikacja rodzin nie powiodła się: {}", it.message)
                    emptyMap()
                }
            }

        var indexed = 0
        for (visit in candidates) {
            val segment = runCatching { segmentService.classify(visit.brandSnapshot, visit.modelSnapshot) }
                .getOrNull()
            val description = VisitDocumentFactory.describe(
                visit, segment?.sizeSegment?.name, segment?.marketTier?.name
            )
            val fingerprint = VisitDocumentFactory.fingerprint(description)
            val state = known[visit.id]

            // Wizyta ruszona, ale treść i format stempla te same — tylko stempel czasu.
            if (state != null && state.fingerprint == fingerprint && state.signatureVersion >= CURRENT_SIGNATURE_VERSION) {
                state.sourceUpdatedAt = visit.updatedAt
                stateRepository.save(state)
                continue
            }

            val families = familiesByStudio[visit.studioId].orEmpty()
            val names = VisitDocumentFactory.serviceNames(visit)

            // Nazwa bez werdyktu = klasyfikator zawiódł, nie „nazwa nieznana".
            // Takiej wizyty NIE stemplujemy: stempel z dziurawymi sygnaturami
            // wyglądałby na gotowy i nigdy nie zostałby poprawiony. Zostaje
            // zaległa i wraca w następnym przebiegu.
            if (names.any { serviceNameKey(it) !in families }) continue

            // Podmiana, nie dopisanie: sygnatury starej wersji zlecenia nie mogą
            // żyć obok nowych i liczyć się podwójnie.
            signatureRepository.deleteByVisitId(visit.id)
            signatureRepository.saveAll(
                names.map { name ->
                    val classified = families.getValue(serviceNameKey(name))
                    VisitServiceSignatureEntity(
                        visitId = visit.id,
                        studioId = visit.studioId,
                        nameKey = classified.nameKey,
                        family = classified.family.name,
                        scope = classified.scope.name
                    )
                }
            )

            stateRepository.save(
                (state ?: VisitIndexStateEntity(
                    visitId = visit.id,
                    studioId = visit.studioId,
                    fingerprint = fingerprint,
                    sourceUpdatedAt = visit.updatedAt
                )).apply {
                    this.fingerprint = fingerprint
                    this.brandKey = visit.brandSnapshot.trim().lowercase().takeIf { it.isNotEmpty() }
                    this.modelKey = visit.modelSnapshot.trim().lowercase().takeIf { it.isNotEmpty() }
                    this.sizeSegment = segment?.sizeSegment?.name
                    this.marketTier = segment?.marketTier?.name
                    this.happenedAt = visit.actualCompletionDate ?: visit.scheduledDate
                    this.signatureVersion = CURRENT_SIGNATURE_VERSION
                    this.indexedAt = Instant.now()
                    this.sourceUpdatedAt = visit.updatedAt
                }
            )
            indexed++
        }

        if (indexed > 0) log.info("[SIMILAR_VISITS] Ostemplowano {} zleceń", indexed)
        return indexed
    }

    companion object {
        /**
         * Wersja FORMATU stempla. Podbicie wymusza ponowne przejście całej historii —
         * fingerprint tego nie umie, bo zmiana formatu nie rusza żadnej wizyty.
         * Wersja 1 = kolumny auta + sygnatury rodzin (V112).
         */
        const val CURRENT_SIGNATURE_VERSION = 1

        /**
         * Co jest „historycznym zleceniem": wszystko poza szkicem i odrzuconą.
         *
         * Wizyta W TRAKCIE trafia do indeksu świadomie — najświeższa robota jest przy
         * wycenie najcenniejsza. Cena takiej wizyty nie jest jeszcze ostateczna, więc
         * pokazuje ją potem osobne oznaczenie w interfejsie, a nie milczenie.
         */
        val INDEXABLE_STATUSES = listOf(
            VisitStatus.IN_PROGRESS,
            VisitStatus.READY_FOR_PICKUP,
            VisitStatus.COMPLETED,
            VisitStatus.ARCHIVED
        )
    }
}
