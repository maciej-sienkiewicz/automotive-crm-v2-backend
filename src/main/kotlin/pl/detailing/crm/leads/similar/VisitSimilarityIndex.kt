package pl.detailing.crm.leads.similar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.time.Instant
import java.util.UUID

/**
 * Relacyjny indeks wyszukiwania „podobnych zleceń" — patrz V111 i V112.
 *
 * Do V111 ta tabela była tylko stanem indeksowania WEKTORÓW, a auto zlecenia żyło
 * w metadanych dokumentu w bazie wektorowej. V112 przenosi auto do kolumn i na tym
 * kończy rolę wektorów: dopasowanie auta zawsze było metadanymi, a dopasowanie
 * usługi przejęły rodziny ([pl.detailing.crm.service.taxonomy.ServiceFamily]).
 * W czasie kliknięcia zostaje zwykłe zapytanie relacyjne.
 *
 * [fingerprint] to skrót treści, która poszła do ostemplowania. Dzięki niemu
 * uzgadniacz odróżnia zmianę ISTOTNĄ (doszła usługa, zmieniło się auto) od dowolnej
 * innej (notatka techniczna, zdjęcia, przesunięcie terminu) i nie płaci za
 * klasyfikacje, które wyszłyby identyczne.
 *
 * [signatureVersion] rozwiązuje problem, którego fingerprint nie widzi: zmiana
 * FORMATU stempla (nowe kolumny, nowa taksonomia) nie rusza żadnej wizyty, więc
 * uzgadniacz kluczowany po updated_at nigdy by jej nie podniósł. Podbicie stałej
 * [VisitSimilarityIndexer.CURRENT_SIGNATURE_VERSION] wymusza przejście całej
 * historii — porcjami, w tempie uzgadniacza.
 */
@Entity
@Table(
    name = "visit_index_state",
    indexes = [Index(name = "ix_visit_index_state_studio", columnList = "studio_id")]
)
class VisitIndexStateEntity(
    @Id
    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "fingerprint", nullable = false, length = 64)
    var fingerprint: String,

    @Column(name = "brand_key", length = 120)
    var brandKey: String? = null,

    @Column(name = "model_key", length = 160)
    var modelKey: String? = null,

    @Column(name = "size_segment", length = 20)
    var sizeSegment: String? = null,

    @Column(name = "market_tier", length = 20)
    var marketTier: String? = null,

    /** Data zakończenia, a gdy zlecenie trwa — planowana. Po niej sortujemy w obrębie rangi. */
    @Column(name = "happened_at")
    var happenedAt: Instant? = null,

    @Column(name = "signature_version", nullable = false)
    var signatureVersion: Int = 0,

    @Column(name = "indexed_at", nullable = false)
    var indexedAt: Instant = Instant.now(),

    @Column(name = "source_updated_at", nullable = false)
    var sourceUpdatedAt: Instant
)

@Repository
interface VisitIndexStateRepository : JpaRepository<VisitIndexStateEntity, UUID> {
    fun findByVisitIdIn(visitIds: Collection<UUID>): List<VisitIndexStateEntity>
    fun deleteByVisitId(visitId: UUID)

    /**
     * Ile zleceń TEGO studia jest w indeksie. Licznik globalny mówiłby studiu,
     * które dopiero zaczyna, że jego historia jest pełna — bo cudza jest.
     */
    fun countByStudioId(studioId: UUID): Long

    /**
     * Kandydaci pod dopasowanie: zlecenia tego studia na TYM aucie albo w TEJ klasie
     * wielkości. Półka rynkowa celowo NIE zawęża (decyzja właściciela produktu:
     * SUV VW kosztuje przy tej samej folii tyle, co SUV Porsche — pracę wyznacza
     * powierzchnia, nie logo). Najświeższe naprzód, bo świeża cena jest przy
     * wycenie najcenniejsza, a kandydatów bywa więcej niż limit.
     */
    @Query(
        """
        SELECT s FROM VisitIndexStateEntity s
        WHERE s.studioId = :studioId
          AND s.signatureVersion >= :version
          AND (
              (s.brandKey = :brandKey AND s.modelKey = :modelKey)
              OR (:sizeSegment IS NOT NULL AND s.sizeSegment = :sizeSegment)
          )
        ORDER BY s.happenedAt DESC NULLS LAST
        """
    )
    fun findCandidates(
        @Param("studioId") studioId: UUID,
        @Param("brandKey") brandKey: String,
        @Param("modelKey") modelKey: String,
        @Param("sizeSegment") sizeSegment: String?,
        @Param("version") version: Int,
        pageable: Pageable
    ): List<VisitIndexStateEntity>
}

/**
 * Sygnatura jednej POZYCJI zlecenia: rodzina i zakres roboty. Wiersz per pozycja,
 * nie per zlecenie — zlecenie wielousługowe pasuje do zapytania, jeśli pasuje
 * którakolwiek jego pozycja. Pozycje ODRZUCONE przez klienta nie mają sygnatur:
 * robota, której nie wykonaliśmy, nie opisuje zlecenia.
 */
@Entity
@Table(
    name = "visit_service_signatures",
    indexes = [
        Index(name = "ix_visit_service_signatures_visit", columnList = "visit_id"),
        Index(name = "ix_visit_service_signatures_studio", columnList = "studio_id")
    ]
)
class VisitServiceSignatureEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name_key", nullable = false, length = 220)
    val nameKey: String,

    @Column(name = "family", nullable = false, length = 30)
    val family: String,

    @Column(name = "scope", nullable = false, length = 20)
    val scope: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface VisitServiceSignatureRepository : JpaRepository<VisitServiceSignatureEntity, UUID> {
    fun findByVisitIdIn(visitIds: Collection<UUID>): List<VisitServiceSignatureEntity>
    fun deleteByVisitId(visitId: UUID)
}

/**
 * Zlecenia zaległe wobec indeksu — nieostemplowane, ruszone po ostatnim stemplu
 * albo ostemplowane STARSZĄ wersją formatu.
 *
 * Zapytanie siedzi TUTAJ, a nie w VisitRepository, świadomie: wiąże wizyty z tabelą
 * należącą do tej funkcji, a moduł wizyt nie ma powodu wiedzieć, że ktoś obok robi
 * z nich indeks wyszukiwania. Zależność idzie w jedną stronę — od funkcji do wizyt.
 */
@Repository
interface VisitIndexCandidateRepository : org.springframework.data.repository.Repository<VisitEntity, UUID> {

    @Query(
        """
        SELECT v FROM VisitEntity v
        WHERE v.status IN :statuses
          AND v.deletedAt IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM VisitIndexStateEntity s
              WHERE s.visitId = v.id
                AND s.sourceUpdatedAt >= v.updatedAt
                AND s.signatureVersion >= :version
          )
        ORDER BY v.updatedAt ASC
        """
    )
    fun findPending(
        @Param("statuses") statuses: Collection<VisitStatus>,
        @Param("version") version: Int,
        pageable: Pageable
    ): List<VisitEntity>
}

/**
 * Odczyt zleceń wskazanych przez dopasowanie — z filtrem studia w samym zapytaniu.
 *
 * Identyfikatory przychodzą z indeksu, który jest per studio już na poziomie
 * zapytania, ale warunek `studio_id` stoi tu jako druga, niezależna bariera:
 * jeden błąd w budowaniu tamtego zapytania nie może zamienić się w cudze ceny
 * na ekranie handlowca.
 */
@Repository
interface SimilarVisitReadRepository : org.springframework.data.repository.Repository<VisitEntity, UUID> {

    @Query(
        """
        SELECT v FROM VisitEntity v
        WHERE v.studioId = :studioId
          AND v.id IN :ids
          AND v.deletedAt IS NULL
        """
    )
    fun findByStudioIdAndIdIn(
        @Param("studioId") studioId: UUID,
        @Param("ids") ids: Collection<UUID>
    ): List<VisitEntity>
}

/**
 * Werdykt zapisany przy parze lead↔zlecenie.
 *
 * Produkcja zapisuje dziś wyłącznie IRRELEVANT — jedyną akcją człowieka jest
 * zdjęcie podpowiedzi z listy. RELEVANT zostaje w enumie, bo w bazie leżą wiersze
 * z okresu, gdy sekcja miała kciuk w górę; kolumna nie ma ograniczenia CHECK,
 * więc usunięcie wartości nie wywróciłoby odczytu, ale zamieniłoby istniejące
 * wiersze w dane, których kod nie umie nazwać.
 */
enum class VisitMatchVerdict { RELEVANT, IRRELEVANT }

/**
 * Zdjęta podpowiedź — patrz V111__visit_similarity.sql.
 *
 * Zapis jest per PARA, nie per zlecenie: „to zlecenie nie pasuje do pytania o mycie"
 * nie znaczy „to zlecenie jest złe". Przy innym leadzie może być najlepszą
 * odpowiedzią, jaką mamy.
 */
@Entity
@Table(
    name = "visit_match_feedback",
    indexes = [Index(name = "ix_visit_match_feedback_studio", columnList = "studio_id, created_at DESC")]
)
class VisitMatchFeedbackEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "verdict", nullable = false, length = 20)
    var verdict: String,

    @Column(name = "created_by", columnDefinition = "uuid")
    val createdBy: UUID? = null,

    @Column(name = "created_by_name", length = 200)
    val createdByName: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface VisitMatchFeedbackRepository : JpaRepository<VisitMatchFeedbackEntity, UUID> {
    fun findByLeadId(leadId: UUID): List<VisitMatchFeedbackEntity>
    fun findByLeadIdAndVisitId(leadId: UUID, visitId: UUID): VisitMatchFeedbackEntity?
}
