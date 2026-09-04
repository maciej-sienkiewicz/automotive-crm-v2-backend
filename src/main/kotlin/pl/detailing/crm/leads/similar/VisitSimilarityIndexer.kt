package pl.detailing.crm.leads.similar

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.segment.VehicleSegmentService
import org.springframework.data.domain.PageRequest
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Zamienia zlecenie w zdanie, po którym da się je znaleźć.
 *
 * Opis jest celowo taki, jakim człowiek opisałby robotę koledze — „Porsche Panamera
 * 2021, segment F, luksusowe: oklejenie full body PPF, powłoka ceramiczna" — bo po
 * drugiej stronie stoi zapytanie klienta napisane dokładnie tak samo potocznie.
 * Osadzenie tabeli z kolumnami byłoby osadzeniem czegoś, czego nikt nigdy nie napisze.
 *
 * Czego w opisie NIE MA i dlaczego:
 *  - KWOTY — liczba w tekście nie czyni dwóch zleceń podobnymi, a wciągnięta do
 *    wektora przyciągałaby zlecenia o zbliżonej cenie i różnym zakresie, czyli
 *    dokładnie odwrotnie, niż potrzeba przy wycenie.
 *  - DANYCH KLIENTA — nazwisko i tablice nie mają wpływu na podobieństwo roboty,
 *    a znalazłyby się w bazie wektorowej bez powodu.
 *  - NOTATEK TECHNICZNYCH — opisują stan konkretnego egzemplarza, nie rodzaj pracy,
 *    i zmieniają się w trakcie wizyty, co kazałoby liczyć wektor od nowa bez zysku.
 */
object VisitDocumentFactory {

    fun describe(visit: VisitEntity, sizeSegment: String?, marketTier: String?): String {
        val car = listOfNotNull(
            visit.brandSnapshot.trim().takeIf { it.isNotEmpty() },
            visit.modelSnapshot.trim().takeIf { it.isNotEmpty() },
            visit.yearOfProductionSnapshot?.toString()
        ).joinToString(" ")

        val classification = listOfNotNull(sizeSegment, marketTier)
            .filter { it.isNotBlank() && it != "UNKNOWN" }
            .joinToString(", ")

        // Usługi w kolejności zapisu i bez powtórzeń: dwie identyczne pozycje to
        // szczegół rozliczeniowy, nie druga usługa.
        val services = visit.serviceItems
            .map { it.serviceName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        return buildString {
            append(car.ifBlank { "Pojazd nieokreślony" })
            if (classification.isNotBlank()) append(" ($classification)")
            append(": ")
            append(services.ifBlank { "brak wykazanych usług" })
        }
    }

    /** Skrót opisu — po nim uzgadniacz poznaje, że wektor wyszedłby identyczny. */
    fun fingerprint(description: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(description.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(64)

    const val META_STUDIO_ID = "studio_id"
    const val META_VISIT_ID = "visit_id"
    const val META_BRAND = "brand"
    const val META_MODEL = "model"
    const val META_SIZE_SEGMENT = "size_segment"
    const val META_MARKET_TIER = "market_tier"
}

/**
 * Utrzymuje indeks wektorowy zleceń w zgodzie z tabelą wizyt.
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
 * Odcisk treści rozstrzyga, czy płacimy za nowe osadzenie: zmiana notatki technicznej
 * czy przesunięcie terminu nie rusza opisu, po którym szukamy.
 */
@Service
class VisitSimilarityIndexer(
    private val candidateRepository: VisitIndexCandidateRepository,
    private val stateRepository: VisitIndexStateRepository,
    private val segmentService: VehicleSegmentService,
    @Qualifier(VisitSimilarityVectorConfig.VISIT_SIMILARITY_VECTOR_STORE)
    private val vectorStore: VectorStore,
    @Value("\${crm.ai.similar-visits.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.similar-visits.reconcile-batch:200}") private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Jedna porcja zaległości. Wielkość porcji jest sufitem kosztu na przebieg:
     * studio wchodzące z pięcioma tysiącami wizyt zapełni indeks w kilkanaście
     * przebiegów zamiast w jednym uderzeniu w API osadzeń.
     */
    @Scheduled(fixedDelayString = "\${crm.ai.similar-visits.reconcile-interval-ms:300000}")
    fun reconcile() {
        if (!enabled) return
        runCatching { indexPending(batchSize) }
            .onFailure { log.warn("[SIMILAR_VISITS] Uzgadnianie indeksu nie powiodło się: {}", it.message) }
    }

    /** @return ile zleceń przeszło przez osadzenie w tym przebiegu. */
    @Transactional(readOnly = true)
    fun indexPending(limit: Int): Int {
        val candidates = candidateRepository.findPending(INDEXABLE_STATUSES, PageRequest.of(0, limit))
        if (candidates.isEmpty()) return 0

        val known = stateRepository.findByVisitIdIn(candidates.map { it.id }).associateBy { it.visitId }
        var indexed = 0

        for (visit in candidates) {
            val segment = runCatching { segmentService.classify(visit.brandSnapshot, visit.modelSnapshot) }
                .getOrNull()
            val description = VisitDocumentFactory.describe(
                visit,
                segment?.sizeSegment?.name,
                segment?.marketTier?.name
            )
            val fingerprint = VisitDocumentFactory.fingerprint(description)
            val state = known[visit.id]

            // Wizyta ruszona, ale opis ten sam — zapisujemy tylko stempel, bez osadzania.
            if (state != null && state.fingerprint == fingerprint) {
                state.sourceUpdatedAt = visit.updatedAt
                stateRepository.save(state)
                continue
            }

            // Podmiana, nie dopisanie: bez usunięcia starego wpisu zlecenie żyłoby
            // w indeksie w dwóch wersjach i wypadało dwa razy w jednym wyniku.
            if (state != null) runCatching { vectorStore.delete(listOf(documentId(visit.id))) }

            vectorStore.add(
                listOf(
                    Document(
                        documentId(visit.id),
                        description,
                        mapOf(
                            VisitDocumentFactory.META_STUDIO_ID to visit.studioId.toString(),
                            VisitDocumentFactory.META_VISIT_ID to visit.id.toString(),
                            VisitDocumentFactory.META_BRAND to visit.brandSnapshot.lowercase(),
                            VisitDocumentFactory.META_MODEL to visit.modelSnapshot.lowercase(),
                            VisitDocumentFactory.META_SIZE_SEGMENT to (segment?.sizeSegment?.name ?: "UNKNOWN"),
                            VisitDocumentFactory.META_MARKET_TIER to (segment?.marketTier?.name ?: "UNKNOWN")
                        )
                    )
                )
            )

            stateRepository.save(
                state?.apply {
                    this.fingerprint = fingerprint
                    this.indexedAt = Instant.now()
                    this.sourceUpdatedAt = visit.updatedAt
                } ?: VisitIndexStateEntity(
                    visitId = visit.id,
                    studioId = visit.studioId,
                    fingerprint = fingerprint,
                    sourceUpdatedAt = visit.updatedAt
                )
            )
            indexed++
        }

        if (indexed > 0) log.info("[SIMILAR_VISITS] Zindeksowano {} zleceń", indexed)
        return indexed
    }

    /**
     * Identyfikator dokumentu = identyfikator wizyty. Dzięki temu podmiana i usunięcie
     * są jednym wywołaniem, bez szukania po metadanych.
     */
    private fun documentId(visitId: UUID): String = visitId.toString()

    companion object {
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
