package pl.detailing.crm.leads.similar.feedback

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.similar.LeadSimilarMatchesRepository
import pl.detailing.crm.leads.similar.VisitMatchFeedbackEntity
import pl.detailing.crm.leads.similar.VisitMatchFeedbackRepository
import pl.detailing.crm.leads.similar.VisitMatchVerdict
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * „Użyj tej ceny" — którą kwotę właściciel FAKTYCZNIE przeniósł do wyceny (V132).
 *
 * Pierwszy sygnał POZYTYWNY w historii tej funkcji: dotąd `VisitMatchVerdict.RELEVANT`
 * istniało w enumie bez żadnej ścieżki zapisu, więc system strukturalnie nie mógł
 * się dowiedzieć, jak wygląda DOBRE dopasowanie. To nie jest kciuk ani ankieta —
 * to czynność, którą właściciel i tak wykonuje (kopiuje kwotę do pozycji), więc
 * nie płacimy podatku UX za zbieranie sygnału.
 */
@Entity
@Table(
    name = "anchor_outcomes",
    indexes = [Index(name = "ix_ao_studio", columnList = "studio_id, created_at DESC")]
)
class AnchorOutcomeEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    /** Konkretna realizacja, której kwotę wzięto — null, gdy wzięto medianę pasma. */
    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID? = null,

    @Column(name = "band_median")
    val bandMedian: Long? = null,

    @Column(name = "price_used", nullable = false)
    val priceUsed: Long,

    @Column(name = "operation", length = 20)
    val operation: String? = null,

    @Column(name = "part", length = 20)
    val part: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "created_by", columnDefinition = "uuid")
    val createdBy: UUID? = null
)

@Repository
interface AnchorOutcomeRepository : JpaRepository<AnchorOutcomeEntity, UUID> {
    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<AnchorOutcomeEntity>
    fun findByLeadId(leadId: UUID): List<AnchorOutcomeEntity>
}

/**
 * Zapis użycia podpowiedzianej ceny + darmowa etykieta RELEVANT na parze
 * lead↔zlecenie — domknięcie problemu jednoklasowego feedbacku.
 */
@Service
class AnchorOutcomeService(
    private val outcomeRepository: AnchorOutcomeRepository,
    private val matchesRepository: LeadSimilarMatchesRepository,
    private val feedbackRepository: VisitMatchFeedbackRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param visitId realizacja, której kwotę wzięto; null = mediana pasma.
     * @param priceUsed kwota przeniesiona do wyceny, w groszach.
     */
    @Transactional
    fun recordPriceUsed(
        studioId: StudioId,
        leadId: UUID,
        visitId: UUID?,
        priceUsed: Long,
        userId: UserId
    ): AnchorOutcomeEntity {
        if (priceUsed <= 0) throw ValidationException("Kwota musi być dodatnia")

        val stored = matchesRepository.findById(leadId).orElse(null)
            ?.takeIf { it.studioId == studioId.value }
            ?: throw NotFoundException("Lead nie ma zapisanego doboru")

        val outcome = outcomeRepository.save(
            AnchorOutcomeEntity(
                studioId = studioId.value,
                leadId = leadId,
                visitId = visitId,
                bandMedian = stored.bandMedian,
                priceUsed = priceUsed,
                createdBy = userId.value
            )
        )

        // Etykieta pozytywna: wskazana realizacja BYŁA użyteczną kotwicą. Zapis
        // per para lead↔zlecenie, symetrycznie do odrzuceń — i idempotentnie.
        if (visitId != null && feedbackRepository.findByLeadIdAndVisitId(leadId, visitId) == null) {
            feedbackRepository.save(
                VisitMatchFeedbackEntity(
                    studioId = studioId.value,
                    leadId = leadId,
                    visitId = visitId,
                    verdict = VisitMatchVerdict.RELEVANT.name,
                    createdBy = userId.value
                )
            )
        }

        log.info("[SIMILAR_VISITS] Lead {} — użyto podpowiedzianej ceny {} gr", leadId, priceUsed)
        return outcome
    }
}
