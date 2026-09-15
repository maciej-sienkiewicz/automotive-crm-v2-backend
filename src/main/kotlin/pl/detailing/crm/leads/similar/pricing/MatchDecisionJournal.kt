package pl.detailing.crm.leads.similar.pricing

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
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Dziennik decyzji doboru — WIERSZ PER KANDYDAT, TAKŻE ODRZUCONY (V130).
 *
 * Do przebudowy grade() zwracał samą rangę, a wyliczone po drodze pokrycie
 * i skupienie ginęły wraz z ramką stosu: na pytanie „dlaczego próg bagażnika
 * wszedł, a full body nie" odpowiadało się śledztwem w kodzie. Ten dziennik
 * zamienia śledztwo w SELECT — i jest jedynym materiałem, na którym da się potem
 * skalibrować progi albo udowodnić, że weryfikator LLM niczego nie dokłada
 * (verifierAgreedWithGate bliskie 100% po kwartale = komponent wypada z kodu).
 *
 * Pola whyItFits/whatDiffers to PRODUKT, nie telemetria: zdania z weryfikatora
 * trafiają na kartę compa przy hydratacji — jeden zapis, dwa zastosowania.
 */
@Entity
@Table(
    name = "lead_match_decisions",
    indexes = [
        Index(name = "ix_lmd_lead", columnList = "lead_id, created_at DESC"),
        Index(name = "ix_lmd_studio_reject", columnList = "studio_id, reject_code, created_at")
    ]
)
class LeadMatchDecisionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "tier", length = 40)
    val tier: String? = null,

    /** DIRECT | ADJUSTED | REJECTED — patrz [CompClass]. */
    @Column(name = "comp_class", nullable = false, length = 20)
    val compClass: String,

    @Column(name = "value_coverage", precision = 6, scale = 3)
    val valueCoverage: BigDecimal? = null,

    @Column(name = "value_focus", precision = 6, scale = 3)
    val valueFocus: BigDecimal? = null,

    @Column(name = "price_ratio", precision = 10, scale = 4)
    val priceRatio: BigDecimal? = null,

    @Column(name = "shown", nullable = false)
    val shown: Boolean,

    @Column(name = "position")
    val position: Int? = null,

    @Column(name = "exploration", nullable = false)
    val exploration: Boolean = false,

    @Column(name = "relaxed_axis", length = 20)
    val relaxedAxis: String? = null,

    @Column(name = "reject_code", length = 40)
    val rejectCode: String? = null,

    @Column(name = "prompt_version", length = 20)
    val promptVersion: String? = null,

    @Column(name = "rules_version", nullable = false)
    val rulesVersion: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    // ── Werdykt weryfikatora (L4) — null, gdy weryfikator nie był uruchamiany ──
    @Column(name = "v_same_operation")
    val verifierSameOperation: Boolean? = null,

    @Column(name = "v_same_part")
    val verifierSamePart: Boolean? = null,

    @Column(name = "v_same_scale")
    val verifierSameScale: Boolean? = null,

    @Column(name = "v_price_comparable")
    val verifierPriceComparable: Boolean? = null,

    /** Licznik kryterium śmierci L4: czy werydykt L4 zgodził się z bramkami. */
    @Column(name = "verifier_agreed_with_gate")
    val verifierAgreedWithGate: Boolean? = null,

    @Column(name = "why_it_fits", length = 200)
    val whyItFits: String? = null,

    @Column(name = "what_differs", length = 200)
    val whatDiffers: String? = null
)

@Repository
interface LeadMatchDecisionRepository : JpaRepository<LeadMatchDecisionEntity, UUID> {

    /** Najświeższy przebieg doboru dla leada — do kart compów przy hydratacji. */
    fun findByLeadIdOrderByCreatedAtDesc(leadId: UUID): List<LeadMatchDecisionEntity>

    /** Nowy przebieg zastępuje poprzedni: dziennik opisuje AKTUALNY dobór, historię trzyma audyt zapisu. */
    @Modifying
    @Query("DELETE FROM LeadMatchDecisionEntity d WHERE d.leadId = :leadId")
    fun deleteByLeadId(@Param("leadId") leadId: UUID)
}
