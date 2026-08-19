package pl.detailing.crm.leads.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Duration
import java.time.Instant

data class LeadAnalyticsDto(
    val from: Instant,
    val to: Instant,
    /** Liczba zapytań, które wpłynęły w okresie. */
    val totalCreated: Int,
    /** Rozkład bieżących statusów leadów z okresu. */
    val byStatus: Map<String, Int>,
    /** Konwersja: ukończone / wszystkie zamknięte (ukończone + przegrane + no-show). */
    val conversionRate: Double?,
    /** Wartość wygranych leadów (grosze). */
    val wonValue: Long,
    /** Wartość leadów wciąż otwartych (grosze). */
    val pipelineValue: Long,
    /** O co pytają: rozkład kategorii + konwersja per kategoria. */
    val categories: List<CategoryStatDto>,
    /** Dlaczego przegrywamy: rozkład powodów w leadach LOST. */
    val lostReasons: List<LostReasonStatDto>,
    /** Mediana czasu pierwszej odpowiedzi w minutach (tylko leady e-mail z odpowiedzią). */
    val medianFirstResponseMinutes: Long?
)

data class CategoryStatDto(
    val code: String?,
    val label: String,
    val count: Int,
    val completed: Int,
    val conversionRate: Double?
)

data class LostReasonStatDto(
    val code: String,
    val label: String,
    val count: Int,
    val share: Double
)

/**
 * The three questions the owner actually asks: how many inquiries become jobs, what
 * are people asking about, and why do we lose. Everything is computed straight off the
 * leads of the requested window — no separate analytics store to drift out of sync.
 */
@Service
class GetLeadAnalyticsHandler(
    private val leadRepository: LeadRepository,
    private val tagService: LeadTagService
) {

    @Transactional(readOnly = true)
    fun handle(studioId: StudioId, from: Instant, to: Instant): LeadAnalyticsDto {
        val leads = leadRepository.findByStudioIdAndCreatedAtBetween(studioId.value, from, to)

        val byStatus = leads.groupingBy { it.status }.eachCount()
        val completed = byStatus[LeadStatus.COMPLETED] ?: 0
        val closed = completed + (byStatus[LeadStatus.LOST] ?: 0) + (byStatus[LeadStatus.NO_SHOW] ?: 0)

        // Oś „o co pytają" liczy się po tagach, a lead może mieć ich kilka — więc
        // ten sam lead liczy się do każdego tematu, którego dotyczyło zapytanie.
        // Suma po tematach jest wtedy większa niż liczba leadów i tak ma być:
        // pytanie brzmi „ile razy pytano o ceramikę", a nie „ile leadów istnieje".
        val tagsByLead = tagService.tagsOf(leads.map { it.id })
        val categories = leads
            .flatMap { lead ->
                val tags = tagsByLead[lead.id].orEmpty()
                if (tags.isEmpty()) listOf(null to lead) else tags.map { it to lead }
            }
            .groupBy({ it.first }, { it.second })
            .map { (tag, group) ->
                val groupCompleted = group.count { it.status == LeadStatus.COMPLETED }
                val groupClosed = group.count {
                    it.status in setOf(LeadStatus.COMPLETED, LeadStatus.LOST, LeadStatus.NO_SHOW)
                }
                CategoryStatDto(
                    code = tag?.name,
                    label = tag?.label ?: "Bez tagu",
                    count = group.size,
                    completed = groupCompleted,
                    conversionRate = ratio(groupCompleted, groupClosed)
                )
            }
            .sortedByDescending { it.count }

        val lost = leads.filter { it.status == LeadStatus.LOST }
        val lostReasons = lost
            .groupBy { it.lostReasonCode }
            .mapNotNull { (reason, group) ->
                reason?.let {
                    LostReasonStatDto(
                        code = it.name,
                        label = it.label,
                        count = group.size,
                        share = group.size.toDouble() / lost.size
                    )
                }
            }
            .sortedByDescending { it.count }

        val responseTimes = leads
            .mapNotNull { lead -> lead.firstResponseAt?.let { Duration.between(lead.createdAt, it) } }
            .filter { !it.isNegative }
            .map { it.toMinutes() }
            .sorted()

        return LeadAnalyticsDto(
            from = from,
            to = to,
            totalCreated = leads.size,
            byStatus = byStatus.mapKeys { it.key.name },
            conversionRate = ratio(completed, closed),
            wonValue = leads.filter { it.status == LeadStatus.COMPLETED }.sumOf { it.estimatedValue },
            pipelineValue = leads
                .filter { it.status in setOf(LeadStatus.NEW, LeadStatus.IN_PROGRESS, LeadStatus.CONFIRMED) }
                .sumOf { it.estimatedValue },
            categories = categories,
            lostReasons = lostReasons,
            medianFirstResponseMinutes = responseTimes.takeIf { it.isNotEmpty() }
                ?.let { it[it.size / 2] }
        )
    }

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator
}
