package pl.detailing.crm.instagram.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramReportEntity
import pl.detailing.crm.instagram.infrastructure.InstagramReportRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

// ── Struktura raportu (payload JSON, renderowany przez frontend) ──────────────

data class ReportEventDto(
    val severity: String,
    val title: String,
    val body: String,
    val permalink: String?
)

data class ReportTopPostDto(
    val username: String,
    val permalink: String,
    val engagement: Int,
    val topicLabel: String,
    val format: String
)

data class ReportRecommendationDto(
    val text: String,
    val reason: String
)

data class ReportPayloadDto(
    val comparisonGroupSize: Int,
    val hasSelf: Boolean,
    val selfUsername: String?,
    val position: PositionDto?,
    val erPct: MetricTriple,
    val postsPerWeek: MetricTriple,
    val activityIndex: MetricTriple,
    val events: List<ReportEventDto>,
    val topPost: ReportTopPostDto?,
    val bestSlotLabel: String?,
    val topTopicLabel: String?,
    val storefrontGaps: List<String>,
    val recommendation: ReportRecommendationDto
)

data class ReportDto(
    val id: String,
    val periodStart: String,
    val periodEnd: String,
    val title: String,
    val lead: String,
    val narrativeSource: String,
    val createdAt: Instant,
    val payload: ReportPayloadDto
)

data class ReportBriefDto(
    val id: String,
    val periodStart: String,
    val periodEnd: String,
    val title: String
)

/**
 * Raport tygodnia: liczby zbiera kod (agregaty modułu), LLM pisze wyłącznie
 * 2–3-zdaniową narrację wstępu na podstawie gotowego payloadu – nie może
 * zhalucynować danych, bo wszystkie liczby w sekcjach wstawia backend.
 * Przy braku klucza AI lub błędzie: deterministyczny szablon.
 */
@Service
class ReportService(
    private val readService: InstagramAnalyticsReadService,
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val reportRepository: InstagramReportRepository,
    private val objectMapper: ObjectMapper,
    @Qualifier("instagramChatClient") private val chatClient: ObjectProvider<ChatClient>,
    @Value("\${instagram.report.ai.enabled:true}") private val aiEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(ReportService::class.java)

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("d.MM")
        private val DAY_LABELS = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")
        private val DAYPART_LABELS = listOf("rano (6–11)", "w południe (11–16)", "wieczorem (16–21)", "nocą (21–6)")
    }

    /** Raport za ostatni zamknięty tydzień; generuje leniwie, jeśli jeszcze nie istnieje. */
    @Transactional
    fun latest(studioId: StudioId): ReportDto? {
        val periodStart = MetricsCalculator.currentWeekStart().minusWeeks(1)

        reportRepository.findByStudioIdAndPeriodStart(studioId.value, periodStart)?.let { return it.toDto() }

        val hasProfiles = studioProfileRepository
            .findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
            .isNotEmpty()
        if (!hasProfiles) return null

        return generate(studioId, periodStart)?.toDto()
    }

    @Transactional(readOnly = true)
    fun list(studioId: StudioId, limit: Int): List<ReportBriefDto> =
        reportRepository.findByStudioIdOrderByPeriodStartDesc(studioId.value, PageRequest.of(0, limit.coerceIn(1, 26)))
            .map { ReportBriefDto(it.id.toString(), it.periodStart.toString(), it.periodEnd.toString(), it.title) }

    @Transactional(readOnly = true)
    fun byId(studioId: StudioId, id: UUID): ReportDto? =
        reportRepository.findById(id).orElse(null)
            ?.takeIf { it.studioId == studioId.value }
            ?.toDto()

    /** Wywoływane też przez orchestrator po niedzielnym syncu – dla wszystkich studiów. */
    @Transactional
    fun generateForAllStudios() {
        val periodStart = MetricsCalculator.currentWeekStart().minusWeeks(1)
        studioProfileRepository.findDistinctStudioIdsByStatus(InstagramProfileStatus.ACTIVE).forEach { studioId ->
            try {
                if (reportRepository.findByStudioIdAndPeriodStart(studioId, periodStart) == null) {
                    generate(StudioId(studioId), periodStart)
                }
            } catch (e: Exception) {
                log.error("Instagram raport: błąd generowania dla studia {}: {}", studioId, e.message, e)
            }
        }
    }

    // ── generacja ─────────────────────────────────────────────────────────────

    private fun generate(studioId: StudioId, periodStart: LocalDate): InstagramReportEntity? {
        val overview = readService.overview(studioId, InstagramAnalyticsReadService.DEFAULT_WEEKS)
        if (overview.profilesCount == 0) return null

        val periodEnd = periodStart.plusDays(6)

        val events = overview.insights.take(4).map {
            ReportEventDto(it.severity, it.title, it.body, it.permalink)
        }

        val content = readService.content(
            studioId = studioId, weeks = 4, sort = "engagement",
            topic = null, format = null, profileId = null, promoOnly = false,
            page = 0, pageSize = 60
        )

        val topPost = content.items.firstOrNull()?.let {
            ReportTopPostDto(it.username, it.permalink, it.engagement, it.topicLabel, it.format)
        }

        val topTopic = content.items
            .filter { it.topic != "INNE" && it.topic != "PROMOCJA" }
            .groupBy { it.topicLabel }
            .filterValues { it.size >= 3 }
            .maxByOrNull { (_, items) -> items.sumOf { it.engagement }.toDouble() / items.size }
            ?.key

        val heatmap = readService.heatmap(studioId, 12)
        val bestSlot = if (heatmap.bestDayOfWeek != null && heatmap.bestDaypart != null) {
            "${DAY_LABELS[heatmap.bestDayOfWeek - 1]} ${DAYPART_LABELS[heatmap.bestDaypart]}"
        } else null

        val recommendation = buildRecommendation(overview, topTopic, bestSlot)

        val payload = ReportPayloadDto(
            comparisonGroupSize = overview.comparisonGroupSize,
            hasSelf = overview.hasSelf,
            selfUsername = overview.selfUsername,
            position = overview.position,
            erPct = overview.erPct,
            postsPerWeek = overview.postsPerWeek,
            activityIndex = overview.activityIndex,
            events = events,
            topPost = topPost,
            bestSlotLabel = bestSlot,
            topTopicLabel = topTopic,
            storefrontGaps = overview.storefront?.gaps ?: emptyList(),
            recommendation = recommendation
        )

        val (lead, source) = buildLead(payload)

        val entity = InstagramReportEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            periodStart = periodStart,
            periodEnd = periodEnd,
            title = "Raport tygodnia ${DATE_FMT.format(periodStart)}–${DATE_FMT.format(periodEnd)}",
            lead = lead,
            narrativeSource = source,
            payload = objectMapper.writeValueAsString(payload),
            createdAt = Instant.now()
        )
        reportRepository.save(entity)
        log.info("Instagram raport: wygenerowano dla studia {} (tydzień {})", studioId, periodStart)
        return entity
    }

    private fun buildRecommendation(
        overview: OverviewResponse,
        topTopic: String?,
        bestSlot: String?
    ): ReportRecommendationDto {
        // Priorytet 1: właściciel wyraźnie publikuje mniej niż grupa
        val ppw = overview.postsPerWeek
        if (overview.hasSelf && ppw.value != null && ppw.benchmark != null && ppw.value < ppw.benchmark / 2) {
            return ReportRecommendationDto(
                text = "Zaplanuj 2–3 posty na najbliższy tydzień – najprościej zdjęcia z bieżących realizacji.",
                reason = "Publikujesz ${"%.1f".format(ppw.value)} posta/tydz. przy ${"%.1f".format(ppw.benchmark)} " +
                    "u typowego studia z Twojej grupy. Regularność to najtańszy sposób na większe zasięgi."
            )
        }

        // Priorytet 2: konkretny temat + pora z danych grupy
        if (topTopic != null || bestSlot != null) {
            val what = topTopic?.let { "post na temat „${it.lowercase()}”" } ?: "post z ostatniej realizacji"
            val whenPart = bestSlot?.let { " – najlepiej w $it" } ?: ""
            return ReportRecommendationDto(
                text = "Opublikuj $what$whenPart.",
                reason = "To temat i pora, które w Twojej grupie zbierają najwięcej reakcji w ostatnich tygodniach."
            )
        }

        // Priorytet 3: braki wizytówki
        overview.storefront?.gaps?.firstOrNull()?.let {
            return ReportRecommendationDto(
                text = it,
                reason = "Kompletna wizytówka profilu to pierwsza rzecz, którą widzi klient porównujący studia."
            )
        }

        return ReportRecommendationDto(
            text = "Utrzymaj obecne tempo publikacji i obserwuj zakładkę Treści.",
            reason = "Brak pilnych sygnałów w tym tygodniu – to dobra wiadomość."
        )
    }

    /** Narracja wstępu: LLM na gotowych liczbach, z twardym fallbackiem szablonowym. */
    private fun buildLead(payload: ReportPayloadDto): Pair<String, String> {
        if (aiEnabled) {
            try {
                val client = chatClient.ifAvailable
                if (client != null) {
                    val result = client.prompt()
                        .system(
                            """
                            Jesteś doradcą marketingowym właściciela studia auto detailingu.
                            Na podstawie danych JSON napisz 2–3 zdania podsumowania tygodnia po polsku.
                            Prosty, ciepły język bez żargonu analitycznego. Nie wymyślaj żadnych liczb –
                            używaj wyłącznie wartości z danych. Nie używaj nagłówków ani list.
                            """.trimIndent()
                        )
                        .user(objectMapper.writeValueAsString(payload))
                        .call()
                        .content()
                    if (!result.isNullOrBlank()) return result.trim() to "LLM"
                }
            } catch (e: Exception) {
                log.warn("Instagram raport: LLM niedostępny, używam szablonu: {}", e.message)
            }
        }
        return templateLead(payload) to "TEMPLATE"
    }

    private fun templateLead(p: ReportPayloadDto): String {
        val sentences = mutableListOf<String>()

        if (p.position != null) {
            sentences += "Twoje studio zajmuje ${p.position.rank}. miejsce wśród ${p.position.total} obserwowanych profili."
        } else {
            sentences += "Obserwujesz ${p.comparisonGroupSize} " +
                if (p.comparisonGroupSize == 1) "profil konkurencji." else "profile konkurencji."
        }

        if (p.events.isNotEmpty()) {
            sentences += "W tym tygodniu odnotowaliśmy ${p.events.size} " +
                (if (p.events.size == 1) "wydarzenie warte uwagi" else "wydarzenia warte uwagi") +
                " – szczegóły poniżej."
        } else {
            sentences += "Tydzień minął spokojnie – bez promocji i nagłych zmian u konkurencji."
        }

        p.recommendation.text.let { sentences += "Nasza sugestia na najbliższe dni: ${it.replaceFirstChar { c -> c.lowercase() }}" }

        return sentences.joinToString(" ")
    }

    private fun InstagramReportEntity.toDto() = ReportDto(
        id = id.toString(),
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        title = title,
        lead = lead,
        narrativeSource = narrativeSource,
        createdAt = createdAt,
        payload = objectMapper.readValue(payload, ReportPayloadDto::class.java)
    )
}
