package pl.detailing.crm.leads.similar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.time.Instant
import java.util.UUID

/**
 * Druga baza wektorowa aplikacji — obok tej, z której korzysta moduł Instagrama.
 *
 * Osobna tabela, a nie wspólna z metadaną „rodzaj dokumentu": zapytanie o podobne
 * zlecenie i zapytanie o inspirację do posta nie mają ze sobą nic wspólnego poza
 * technologią, a jeden zbiór wektorów znaczyłby, że każde wyszukiwanie po jednej
 * stronie musi pamiętać o odfiltrowaniu drugiej. Pierwsze zapomniane filtrowanie
 * pokazywałoby handlowcowi cudze posty jako „podobne zlecenia".
 *
 * Bean jest nazwany, bo autokonfiguracja Spring AI wystawia własny [VectorStore]
 * wskazujący na tabelę Instagrama — bez kwalifikatora wstrzyknięcie byłoby loterią.
 * Schemat tworzy sama biblioteka (initializeSchema), tak jak dla tamtej tabeli:
 * kształt należy do Spring AI i przy jego podniesieniu potrafi się zmienić, więc
 * opisanie go w migracji znaczyłoby utrzymywanie kopii cudzego kontraktu.
 */
@Configuration
class VisitSimilarityVectorConfig {

    @Bean(VISIT_SIMILARITY_VECTOR_STORE)
    fun visitSimilarityVectorStore(
        jdbcTemplate: JdbcTemplate,
        embeddingModel: EmbeddingModel,
        @Value("\${crm.ai.similar-visits.vector-table:visit_similarity_vectors}") table: String
    ): VectorStore =
        PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .vectorTableName(table)
            .initializeSchema(true)
            .build()

    companion object {
        const val VISIT_SIMILARITY_VECTOR_STORE = "visitSimilarityVectorStore"
    }
}

/**
 * Co i kiedy trafiło do indeksu — patrz V111__visit_similarity.sql.
 *
 * [fingerprint] to skrót opisu, który poszedł do osadzenia. Dzięki niemu uzgadniacz
 * odróżnia zmianę ISTOTNĄ (doszła usługa, zmieniło się auto) od dowolnej innej
 * (notatka techniczna, zdjęcia, przesunięcie terminu) i nie płaci za wektor,
 * który wyszedłby identyczny.
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

    @Column(name = "indexed_at", nullable = false)
    var indexedAt: Instant = Instant.now(),

    @Column(name = "source_updated_at", nullable = false)
    var sourceUpdatedAt: Instant
)

@Repository
interface VisitIndexStateRepository : JpaRepository<VisitIndexStateEntity, UUID> {
    fun findByVisitIdIn(visitIds: Collection<UUID>): List<VisitIndexStateEntity>
    fun deleteByVisitId(visitId: UUID)
}

/**
 * Zlecenia zaległe wobec indeksu — nieindeksowane albo ruszone po ostatnim osadzeniu.
 *
 * Zapytanie siedzi TUTAJ, a nie w VisitRepository, świadomie: wiąże wizyty z tabelą
 * należącą do tej funkcji, a moduł wizyt nie ma powodu wiedzieć, że ktoś obok robi
 * z nich indeks wektorowy. Zależność idzie w jedną stronę — od funkcji do wizyt.
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
              WHERE s.visitId = v.id AND s.sourceUpdatedAt >= v.updatedAt
          )
        ORDER BY v.updatedAt ASC
        """
    )
    fun findPending(
        @Param("statuses") statuses: Collection<VisitStatus>,
        pageable: Pageable
    ): List<VisitEntity>
}

/**
 * Odczyt zleceń wskazanych przez dobór — z filtrem studia w samym zapytaniu.
 *
 * Identyfikatory przychodzą z bazy wektorowej, czyli ze zbioru wspólnego dla
 * wszystkich studiów. Gdyby ten odczyt ufał, że są już przefiltrowane, jeden błąd
 * w budowaniu filtra metadanych zamieniłby się w cudze ceny na ekranie. Warunek
 * `studio_id` stoi tu jako druga, niezależna bariera.
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

/** Werdykt człowieka o trafności dopasowania. */
enum class VisitMatchVerdict { RELEVANT, IRRELEVANT }

/**
 * Ocena pary (lead, zlecenie) — patrz V111__visit_similarity.sql.
 *
 * Odrzucona para nigdy nie wraca przy tym leadzie: pokazanie jej drugi raz po tym,
 * jak człowiek powiedział „nie", jest gorsze niż niepokazanie niczego.
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
