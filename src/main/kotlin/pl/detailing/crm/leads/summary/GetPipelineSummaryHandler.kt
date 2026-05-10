package pl.detailing.crm.leads.summary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class GetPipelineSummaryHandler(
    private val leadRepository: LeadRepository
) {
    private val log = LoggerFactory.getLogger(GetPipelineSummaryHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: GetPipelineSummaryQuery): PipelineSummaryResult =
        withContext(Dispatchers.IO) {
            val allLeads = if (query.sourceFilter != null && query.sourceFilter.isNotEmpty()) {
                leadRepository.findByStudioIdWithSourceFilter(
                    studioId = query.studioId.value,
                    sources = query.sourceFilter
                )
            } else {
                leadRepository.findByStudioIdWithSourceFilter(
                    studioId = query.studioId.value,
                    sources = null
                )
            }

            val newLeads = allLeads.filter { it.status == LeadStatus.NEW }
            val inProgress = allLeads.filter { it.status == LeadStatus.IN_PROGRESS }
            // CONFIRMED + COMPLETED both represent successfully converted leads
            val converted = allLeads.filter { it.status == LeadStatus.CONFIRMED || it.status == LeadStatus.COMPLETED }

            val now = Instant.now()
            val zoneId = ZoneId.of("Europe/Warsaw")

            val startOfMonth = now.atZone(zoneId)
                .withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0)
                .toInstant()

            val startOfPreviousMonth = now.atZone(zoneId)
                .minusMonths(1)
                .withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0)
                .toInstant()

            // Kafelek 1: Do obsłużenia — leady ze statusem NEW (nikt jeszcze nie odpowiedział)
            val awaitingFirstContact = newLeads
            val avgWaitingTimeMinutes = if (awaitingFirstContact.isEmpty()) 0L else
                awaitingFirstContact.map { ChronoUnit.MINUTES.between(it.createdAt, now) }.average().toLong()

            // Kafelek 2: Współczynnik konwersji — bieżący miesiąc vs poprzedni
            val leadsCreatedThisMonth = allLeads.filter { it.createdAt >= startOfMonth }
            val convertedThisMonth = converted.filter { it.updatedAt >= startOfMonth }
            val leadsCreatedPreviousMonth = allLeads.filter { it.createdAt >= startOfPreviousMonth && it.createdAt < startOfMonth }
            val convertedPreviousMonth = converted.filter { it.updatedAt >= startOfPreviousMonth && it.updatedAt < startOfMonth }

            val conversionRateThisMonth = if (leadsCreatedThisMonth.isEmpty()) 0.0 else
                convertedThisMonth.size.toDouble() / leadsCreatedThisMonth.size.toDouble() * 100.0
            val conversionRatePreviousMonth = if (leadsCreatedPreviousMonth.isEmpty()) 0.0 else
                convertedPreviousMonth.size.toDouble() / leadsCreatedPreviousMonth.size.toDouble() * 100.0
            val conversionRateTrendPp = conversionRateThisMonth - conversionRatePreviousMonth

            // Kafelek 3: Zrealizowane (ten miesiąc) — wartość i liczba (CONFIRMED + COMPLETED)
            val convertedValueThisMonth = convertedThisMonth.sumOf { it.estimatedValue }
            val convertedCountThisMonth = convertedThisMonth.size

            // Kafelek 4: Ryzyko utraty — IN_PROGRESS bez interakcji od 3+ dni (wartość) / 24+ h (liczba)
            val threshold3Days = now.minus(3, ChronoUnit.DAYS)
            val threshold24Hours = now.minus(24, ChronoUnit.HOURS)
            val atRiskValue = inProgress.filter { it.updatedAt < threshold3Days }.sumOf { it.estimatedValue }
            val atRiskCount = inProgress.count { it.updatedAt < threshold24Hours }

            log.debug(
                "[LEADS] Pipeline summary: studioId={}, awaitingContact={}, conversionRate={}%, convertedValue={}, atRiskValue={}",
                query.studioId.value, awaitingFirstContact.size, "%.1f".format(conversionRateThisMonth),
                convertedValueThisMonth, atRiskValue
            )

            PipelineSummaryResult(
                awaitingFirstContactCount = awaitingFirstContact.size,
                avgWaitingTimeMinutes = avgWaitingTimeMinutes,
                conversionRateThisMonth = conversionRateThisMonth,
                conversionRateTrendPp = conversionRateTrendPp,
                convertedValueThisMonth = convertedValueThisMonth,
                convertedCountThisMonth = convertedCountThisMonth,
                atRiskValue = atRiskValue,
                atRiskCount = atRiskCount,
                newLeadsCount = newLeads.size
            )
        }
}

data class PipelineSummaryResult(
    val awaitingFirstContactCount: Int,
    val avgWaitingTimeMinutes: Long,
    val conversionRateThisMonth: Double,
    val conversionRateTrendPp: Double,
    val convertedValueThisMonth: Long,
    val convertedCountThisMonth: Int,
    val atRiskValue: Long,
    val atRiskCount: Int,
    val newLeadsCount: Int
)
