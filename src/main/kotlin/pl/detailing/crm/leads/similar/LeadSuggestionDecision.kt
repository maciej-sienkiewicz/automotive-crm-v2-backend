package pl.detailing.crm.leads.similar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Dziennik doboru sugestii — WIERSZ PER KANDYDAT, TAKŻE ODRZUCONY.
 *
 * Powstał z konkretnego pytania, na które nie dało się odpowiedzieć: „dlaczego przy
 * zapytaniu o renowację reflektorów automat podsunął okresowy serwis powłoki
 * ceramicznej?". Zapisany był wyłącznie wynik — dwa identyfikatory usług — więc
 * odpowiedź wymagała odtwarzania rozumowania modelu z pamięci. Pole `reasoning`
 * z odpowiedzi modelu było parsowane i wyrzucane, a jedyny cytat był jeden na cały
 * werdykt i nigdy nie wracał z bazy.
 *
 * Ten dziennik zamienia śledztwo w SELECT: dla każdej pozycji, którą model wskazał,
 * stoi tu etap, na którym się zatrzymała, i cytat, którym została uzasadniona.
 * Bliźniak [pl.detailing.crm.leads.similar.pricing.LeadMatchDecisionEntity] robi
 * to samo po stronie „Podobnych zleceń".
 *
 * Nowy przebieg zastępuje poprzedni — dziennik opisuje AKTUALNY dobór, nie historię.
 */
@Entity
@Table(
    name = "lead_suggestion_decisions",
    indexes = [
        Index(name = "ix_lsd_lead", columnList = "lead_id, created_at DESC"),
        Index(name = "ix_lsd_studio_stage", columnList = "studio_id, stage, created_at")
    ]
)
class LeadSuggestionDecisionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    /** Null, gdy model wskazał numer spoza cennika — wtedy nie ma czego identyfikować. */
    @Column(name = "service_id", columnDefinition = "uuid")
    val serviceId: UUID? = null,

    @Column(name = "service_name", nullable = false, length = 200)
    val serviceName: String,

    /** Do której potrzeby z zapytania pozycja była podpięta. */
    @Column(name = "need_index", nullable = false)
    val needIndex: Int,

    /**
     * Etap, na którym pozycja się zatrzymała, albo SHOWN — patrz stałe STAGE_*
     * w [LeadServiceIntentService]. Bez CHECK-a w bazie (spójnie z V101): źródłem
     * prawdy są stałe w kodzie.
     */
    @Column(name = "stage", nullable = false, length = 30)
    val stage: String,

    /** Cytat, którym model uzasadnił tę pozycję — także wtedy, gdy nie przeszedł weryfikacji. */
    @Column(name = "quote", length = 300)
    val quote: String? = null,

    /** ANSWER | UPSELL | UNKNOWN — patrz [SuggestionRole]. */
    @Column(name = "role", nullable = false, length = 20)
    val role: String,

    @Column(name = "prompt_version", nullable = false, length = 20)
    val promptVersion: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface LeadSuggestionDecisionRepository : JpaRepository<LeadSuggestionDecisionEntity, UUID> {

    fun findByLeadIdOrderByCreatedAtDesc(leadId: UUID): List<LeadSuggestionDecisionEntity>

    @Modifying
    @Query("DELETE FROM LeadSuggestionDecisionEntity d WHERE d.leadId = :leadId")
    fun deleteByLeadId(@Param("leadId") leadId: UUID)
}
