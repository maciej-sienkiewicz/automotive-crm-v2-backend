package pl.detailing.crm.leads.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.conversation.LeadConversationStateService
import pl.detailing.crm.leads.conversation.LeadReplyState
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.DateRangeFilter
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Analityka leadów: co wpłynęło, co z tego wyszło i czego się z tego dowiadujemy.
 *
 * Wszystko liczy się wprost z leadów okna — nie ma osobnego magazynu, który mógłby
 * rozjechać się z prawdą. Okno bywa roczne, więc to kilkaset wierszy plus ich tagi
 * i historia statusów: dwa dodatkowe zapytania paczkowe, nie N+1.
 *
 * Reguła, która rządzi całym plikiem: żadna liczba nie wychodzi stąd bez progu
 * wiarygodności. Przy trzech leadach „skuteczność 33%" nie jest wiedzą, tylko
 * przypadkiem, a pokazana bez zastrzeżenia zostanie potraktowana jak wiedza.
 */
@Service
class GetLeadAnalyticsHandler(
    private val leadRepository: LeadRepository,
    private val historyRepository: LeadStatusHistoryRepository,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
    private val conversationStates: LeadConversationStateService
) {

    @Transactional(readOnly = true)
    fun handle(studioId: StudioId, from: Instant, to: Instant): LeadAnalyticsDto {
        val leads = leadRepository.findByStudioIdAndCreatedAtBetween(studioId.value, from, to)
        val leadIds = leads.map { it.id }

        /*
         * Moment decyzji klienta bierze się z historii statusów, a nie z samego leada:
         * lead niesie tylko stan bieżący, więc „kiedy się zdecydował" wie wyłącznie
         * wpis o przejściu na REZERWACJĘ. Pobrane raz i podane dwóm rachunkom —
         * dwa identyczne zapytania po to samo byłyby czystą stratą.
         *
         * Lead potrafi wrócić do rezerwacji po odwołaniu, więc z każdego bierzemy
         * pierwsze takie przejście: decyzja zapada raz.
         */
        val firstDecisionAt: Map<UUID, Instant> =
            if (leadIds.isEmpty()) emptyMap()
            else historyRepository.findByLeadIdIn(leadIds)
                .filter { it.toStatus == LeadStatus.CONFIRMED }
                .groupBy { it.leadId }
                .mapNotNull { (leadId, entries) ->
                    entries.minByOrNull { entry -> entry.createdAt }?.let { leadId to it.createdAt }
                }
                .toMap()

        val windowLength = Duration.between(from, to)
        val byStatus = leads.groupingBy { it.status }.eachCount()
        val completed = byStatus[LeadStatus.COMPLETED] ?: 0
        val closed = completed + (byStatus[LeadStatus.LOST] ?: 0) + (byStatus[LeadStatus.NO_SHOW] ?: 0)

        return LeadAnalyticsDto(
            from = from,
            to = to,
            totalCreated = leads.size,
            byStatus = byStatus.mapKeys { it.key.name },
            conversionRate = ratio(completed, closed),
            wonValue = leads.filter { it.status == LeadStatus.COMPLETED }.sumOf { it.estimatedValue },
            lostValue = leads.filter { it.status in LOST_STATUSES }.sumOf { it.estimatedValue },
            pipelineValue = leads.filter { it.status in OPEN_STATUSES }.sumOf { it.estimatedValue },
            categories = categories(studioId, leads),
            lostReasons = lostReasons(leads),
            medianFirstResponseMinutes = medianFirstResponseMinutes(leads),
            medianDaysToDecision = medianDaysToDecision(leads, firstDecisionAt),
            inquiriesByWeekday = inquiriesByWeekday(leads),
            decisionsByWeekday = decisionsByWeekday(firstDecisionAt),
            inquiriesByMonthDay = inquiriesByMonthDay(leads),
            responseImpact = responseImpact(leads),
            vehicleOutliers = vehicleOutliers(leads, ratio(completed, closed)),
            timeline = timeline(leads, from, to),
            weekdayMatrix = weekdayMatrix(studioId, leads),
            bySource = bySource(leads),
            awaiting = awaiting(studioId),
            leaks = leaks(leads),
            // Poprzednie okno tej samej długości — jedyny punkt odniesienia, jaki
            // właściciel ma bez wychodzenia z ekranu. Bez niego kwota wygranych jest
            // liczbą bez skali: nie wiadomo, czy to dobrze, czy źle.
            wonValuePrevious = leadRepository
                .findByStudioIdAndCreatedAtBetween(studioId.value, from.minus(windowLength), from)
                .filter { it.status == LeadStatus.COMPLETED }
                .sumOf { it.estimatedValue },
            confirmedValueThisWeek = confirmedValueThisWeek(leads, firstDecisionAt)
        )
    }

    // ── O co pytają i w czym wygrywamy ─────────────────────────────────────

    /**
     * Oś „o co pytają" liczy się po tagach, a lead może mieć ich kilka — więc ten sam
     * lead liczy się do każdego tematu, którego dotyczyło zapytanie. Suma po tematach
     * jest wtedy większa niż liczba leadów i tak ma być: pytanie brzmi „ile razy
     * pytano o ceramikę", a nie „ile leadów istnieje".
     *
     * Świadomie po tagach, nie po wycenionych pozycjach. Pozycje pojawiają się dopiero
     * wtedy, gdy ktoś zdążył zrobić wycenę, więc lead przegrany na starcie nie ma ich
     * wcale — a to jest dokładnie ten lead, o którym pytamy „dlaczego przegraliśmy".
     * Liczenie po wycenach mierzyłoby własną pracę, nie zainteresowanie klientów.
     */
    private fun categories(studioId: StudioId, leads: List<LeadEntity>): List<CategoryStatDto> {
        val tagsByLead = tagService.tagsOf(leads.map { it.id })
        val tagLabels = tagCatalog.labelsByCode(studioId)
        return leads
            .flatMap { lead ->
                val tags = tagsByLead[lead.id].orEmpty()
                if (tags.isEmpty()) listOf(null to lead) else tags.map { it to lead }
            }
            .groupBy({ it.first }, { it.second })
            .map { (tag, group) ->
                val groupCompleted = group.count { it.status == LeadStatus.COMPLETED }
                val groupLost = group.count { it.status in LOST_STATUSES }
                CategoryStatDto(
                    code = tag,
                    label = tag?.let { tagLabels[it] ?: it } ?: "Bez tagu",
                    count = group.size,
                    completed = groupCompleted,
                    lost = groupLost,
                    conversionRate = ratio(groupCompleted, groupCompleted + groupLost)
                )
            }
            .sortedByDescending { it.count }
    }

    private fun lostReasons(leads: List<LeadEntity>): List<LostReasonStatDto> {
        val lost = leads.filter { it.status == LeadStatus.LOST }
        if (lost.isEmpty()) return emptyList()
        return lost
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
    }

    // ── Rytm tygodnia i miesiąca ───────────────────────────────────────────

    /**
     * Wszystkie rozkłady w czasie lokalnym studia. Zapytanie z 23:40 w piątek jest
     * zapytaniem piątkowym — dla kogoś, kto planuje dyżur przy skrzynce, UTC nie
     * istnieje.
     */
    private fun inquiriesByWeekday(leads: List<LeadEntity>): List<WeekdayStatDto> {
        val counts = leads.groupingBy { it.createdAt.localDate().dayOfWeek.value }.eachCount()
        return (1..7).map { WeekdayStatDto(it, counts[it] ?: 0) }
    }

    /**
     * „Kiedy się decydują" to dzień przejścia na REZERWACJĘ, a nie dzień zamknięcia
     * leada. Rezerwacja jest momentem decyzji klienta; zrealizowanie to termin, który
     * wybraliśmy razem z nim tygodnie później, i o niczyjej decyzji nie mówi.
     */
    private fun decisionsByWeekday(firstDecisionAt: Map<UUID, Instant>): List<WeekdayStatDto> {
        val counts = firstDecisionAt.values
            .groupingBy { it.localDate().dayOfWeek.value }
            .eachCount()
        return (1..7).map { WeekdayStatDto(it, counts[it] ?: 0) }
    }

    private fun inquiriesByMonthDay(leads: List<LeadEntity>): List<MonthDayStatDto> {
        val counts = leads.groupingBy { it.createdAt.localDate().dayOfMonth }.eachCount()
        return (1..31).map { MonthDayStatDto(it, counts[it] ?: 0) }
    }

    // ── Czas reakcji ───────────────────────────────────────────────────────

    private fun medianFirstResponseMinutes(leads: List<LeadEntity>): Long? =
        leads
            .mapNotNull { lead -> lead.firstResponseAt?.let { Duration.between(lead.createdAt, it) } }
            .filter { !it.isNegative }
            .map { it.toMinutes() }
            .median()

    /**
     * Ile dni mija od zapytania do rezerwacji. Odpowiada na pytanie „po ilu dniach
     * ciszy mogę odpuścić" — jedyne, które pozwala przestać wracać do leadów, z
     * których nic nie będzie.
     */
    private fun medianDaysToDecision(
        leads: List<LeadEntity>,
        firstDecisionAt: Map<UUID, Instant>
    ): Long? {
        val createdAt = leads.associate { it.id to it.createdAt }
        return firstDecisionAt
            .mapNotNull { (leadId, decided) ->
                val start = createdAt[leadId] ?: return@mapNotNull null
                ChronoUnit.DAYS.between(start, decided).takeIf { it >= 0 }
            }
            .median()
    }

    /**
     * Czy szybka odpowiedź podnosi skuteczność.
     *
     * Werdykt zapada na jednym podziale — w dobę albo po dobie — a nie na wszystkich
     * czterech przedziałach naraz. Przy kilkudziesięciu leadach na przedział przypada
     * kilka sztuk i różnice między nimi są szumem; podział na pół daje dwie próby,
     * które da się porównać. Przedziały zostają, bo pokazują kształt, ale zdanie
     * pod wykresem opiera się na tym jednym cięciu.
     */
    private fun responseImpact(leads: List<LeadEntity>): ResponseImpactDto {
        val byBucket = leads.groupBy { responseBucketKey(it) }
        val buckets = RESPONSE_BUCKET_ORDER.map { key ->
            val group = byBucket[key].orEmpty()
            val won = group.count { it.status == LeadStatus.COMPLETED }
            val groupClosed = group.count { it.status in CLOSED_STATUSES }
            ResponseBucketDto(key, group.size, won, groupClosed, ratio(won, groupClosed))
        }

        val fast = FAST_KEYS.flatMap { byBucket[it].orEmpty() }
        val slow = SLOW_KEYS.flatMap { byBucket[it].orEmpty() }
        val fastClosed = fast.count { it.status in CLOSED_STATUSES }
        val slowClosed = slow.count { it.status in CLOSED_STATUSES }
        val fastRate = ratio(fast.count { it.status == LeadStatus.COMPLETED }, fastClosed)
        val slowRate = ratio(slow.count { it.status == LeadStatus.COMPLETED }, slowClosed)

        val verdict = when {
            fastClosed < MIN_SAMPLE || slowClosed < MIN_SAMPLE -> "NOT_ENOUGH_DATA"
            fastRate == null || slowRate == null -> "NOT_ENOUGH_DATA"
            fastRate - slowRate >= RELATION_THRESHOLD -> "FASTER_WINS"
            else -> "NO_RELATION"
        }
        return ResponseImpactDto(buckets, verdict, fastRate, slowRate)
    }

    private fun responseBucketKey(lead: LeadEntity): String {
        val response = lead.firstResponseAt ?: return "NO_REPLY"
        val minutes = Duration.between(lead.createdAt, response).toMinutes()
        if (minutes < 0) return "NO_REPLY"
        return when {
            minutes < 60 -> "UNDER_1H"
            minutes < 4 * 60 -> "UNDER_4H"
            minutes < 24 * 60 -> "UNDER_24H"
            else -> "OVER_24H"
        }
    }

    // ── Odstępstwa ─────────────────────────────────────────────────────────

    /**
     * Marki, w których wyraźnie odstajemy od własnej średniej.
     *
     * Trzy zabezpieczenia przed wyprodukowaniem prawidłowości z niczego: minimalna
     * próba rozstrzygniętych leadów, minimalna różnica wobec średniej i twardy limit
     * długości listy. To ma być zdanie „popatrz na to", a nie tabela do przejrzenia —
     * a jeżeli nic nie odstaje, właściwą odpowiedzią jest pusta lista i komunikat
     * „nie wykryto odstępstw", nie najbliższy kandydat z brzegu.
     *
     * Po marce, nie po modelu: model dzieli i tak niewielką próbę na tyle kawałków,
     * że każdy z nich staje się anegdotą.
     */
    private fun vehicleOutliers(leads: List<LeadEntity>, average: Double?): List<VehicleOutlierDto> {
        if (average == null) return emptyList()
        return leads
            .filter { !it.vehicleBrand.isNullOrBlank() }
            .groupBy { it.vehicleBrand!!.trim() }
            .mapNotNull { (brand, group) ->
                val groupClosed = group.count { it.status in CLOSED_STATUSES }
                if (groupClosed < MIN_OUTLIER_SAMPLE) return@mapNotNull null
                val won = group.count { it.status == LeadStatus.COMPLETED }
                val rate = won.toDouble() / groupClosed
                val gap = rate - average
                if (kotlin.math.abs(gap) < OUTLIER_THRESHOLD) return@mapNotNull null
                VehicleOutlierDto(
                    label = brand,
                    count = group.size,
                    won = won,
                    closed = groupClosed,
                    winRate = rate,
                    direction = if (gap > 0) "ABOVE" else "BELOW"
                )
            }
            .sortedByDescending { kotlin.math.abs(it.winRate - average) }
            .take(MAX_OUTLIERS)
    }

    // ── Trend ──────────────────────────────────────────────────────────────

    /**
     * Tygodnie dla okien do kwartału, miesiące dla dłuższych. Rok w tygodniach to
     * 52 słupki po dwa piksele — kształt niknie w szumie, a właśnie kształt jest tu
     * jedyną treścią.
     */
    private fun timeline(leads: List<LeadEntity>, from: Instant, to: Instant): List<TimelinePointDto> {
        val start = from.localDate()
        val end = to.localDate()
        val monthly = ChronoUnit.DAYS.between(start, end) > MONTHLY_THRESHOLD_DAYS

        val buckets = linkedMapOf<LocalDate, MutableList<LeadEntity>>()
        var cursor = periodStart(start, monthly)
        while (!cursor.isAfter(end)) {
            buckets[cursor] = mutableListOf()
            cursor = if (monthly) cursor.plusMonths(1) else cursor.plusWeeks(1)
        }
        leads.forEach { lead ->
            val key = periodStart(lead.createdAt.localDate(), monthly)
            buckets[key]?.add(lead)
        }

        return buckets.map { (periodStart, group) ->
            val won = group.filter { it.status == LeadStatus.COMPLETED }
            val lost = group.filter { it.status in LOST_STATUSES }
            val open = group.filter { it.status in OPEN_STATUSES }
            TimelinePointDto(
                periodStart = periodStart,
                created = group.size,
                won = won.size,
                lost = lost.size,
                winRate = ratio(won.size, won.size + lost.size),
                wonValue = won.sumOf { it.estimatedValue },
                lostValue = lost.sumOf { it.estimatedValue },
                openValue = open.sumOf { it.estimatedValue }
            )
        }
    }

    // ── Która usługa, w który dzień ─────────────────────────────────────────

    /**
     * Macierz usługa × dzień tygodnia, wiersze od najdroższej usługi.
     *
     * Zwykły słupek „ile zapytań w poniedziałek" mówi tylko, kiedy jest ruch —
     * a ruch sam w sobie nie jest ani przychodem, ani problemem. Dopiero rozbicie
     * na usługi i ustawienie wierszy według średniej wyceny odpowiada na pytanie,
     * które ma konsekwencje w grafiku: czy drogie zapytania przychodzą w innych
     * dniach niż tanie, i czy w tych dniach jest przy skrzynce ktoś, kto potrafi
     * je obsłużyć.
     *
     * Ogon zwinięty do „Pozostałe": macierz ma się dać ogarnąć wzrokiem, a przy
     * dziesięciu wierszach po siedem komórek zostaje jedno zapytanie na kratkę
     * i widać już tylko przypadek.
     */
    private fun weekdayMatrix(studioId: StudioId, leads: List<LeadEntity>): List<WeekdayMatrixRowDto> {
        if (leads.isEmpty()) return emptyList()
        val tagsByLead = tagService.tagsOf(leads.map { it.id })
        val tagLabels = tagCatalog.labelsByCode(studioId)

        // Ten sam rozkład co w „o co pytają": lead z dwoma tagami liczy się do obu.
        val rows = leads
            .flatMap { lead ->
                val tags = tagsByLead[lead.id].orEmpty()
                if (tags.isEmpty()) listOf(null to lead) else tags.map { it to lead }
            }
            .groupBy({ it.first }, { it.second })
            .map { (tag, group) -> buildRow(tag, tag?.let { tagLabels[it] ?: it } ?: "Bez tagu", group) }

        if (rows.size <= MAX_MATRIX_ROWS) return rows.sortedWith(BY_VALUE_DESC)

        // Do zwinięcia idą wiersze najrzadsze, nie najtańsze: usługa droga i rzadka
        // jest właśnie tą, o której warto wiedzieć, że wpada w soboty.
        val keep = rows.sortedByDescending { it.total }.take(MAX_MATRIX_ROWS - 1)
        val rest = rows - keep.toSet()
        val restRow = WeekdayMatrixRowDto(
            code = null,
            label = "Pozostałe",
            averageValue = null,
            counts = (0 until 7).map { day -> rest.sumOf { it.counts[day] } },
            total = rest.sumOf { it.total }
        )
        return keep.sortedWith(BY_VALUE_DESC) + restRow
    }

    private fun buildRow(code: String?, label: String, group: List<LeadEntity>): WeekdayMatrixRowDto {
        val counts = IntArray(7)
        group.forEach { counts[it.createdAt.localDate().dayOfWeek.value - 1]++ }
        // Średnia tylko po wycenionych: zapytanie bez wyceny nie znaczy „za zero zł",
        // znaczy „jeszcze nikt nie policzył".
        val priced = group.filter { it.estimatedValue > 0 }
        return WeekdayMatrixRowDto(
            code = code,
            label = label,
            averageValue = priced.takeIf { it.isNotEmpty() }?.let { it.sumOf { lead -> lead.estimatedValue } / it.size },
            counts = counts.toList(),
            total = group.size
        )
    }

    private fun periodStart(date: LocalDate, monthly: Boolean): LocalDate =
        if (monthly) date.withDayOfMonth(1)
        else date.minusDays((date.dayOfWeek.value - 1).toLong())

    // ── Pieniądze czekające na odpowiedź ───────────────────────────────────

    /**
     * Stan bieżący całego studia, nie okno raportu — patrz [AwaitingWorkDto].
     *
     * Liczy się tylko to, w czym ostatnie słowo należy do klienta. Lead, w którym
     * to my napisaliśmy ostatni, nie jest zaległością, tylko czekaniem na decyzję —
     * mieszanie tych dwóch rzeczy zamieniłoby listę zadań w listę wszystkiego.
     */
    private fun awaiting(studioId: StudioId): AwaitingWorkDto {
        val open = leadRepository.findByStudioIdAndStatusIn(studioId.value, OPEN_STATUSES)
        if (open.isEmpty()) return AwaitingWorkDto(0, 0, null)

        val states = conversationStates.statesOf(studioId.value, open)
        val waiting = open.mapNotNull { lead ->
            val state = states[lead.id] ?: return@mapNotNull null
            if (state.replyState != LeadReplyState.AWAITING_OUR_REPLY) return@mapNotNull null
            val since = state.waitingSince ?: return@mapNotNull null
            lead to since
        }
        if (waiting.isEmpty()) return AwaitingWorkDto(0, 0, null)

        val now = Instant.now()
        val oldest = waiting.minByOrNull { it.second }
        return AwaitingWorkDto(
            value = waiting.sumOf { it.first.estimatedValue },
            count = waiting.size,
            oldest = oldest?.let { (lead, since) ->
                AwaitingLeadDto(
                    leadId = lead.id.toString(),
                    // Nazwisko, jeśli je znamy; adres albo numer, jeśli nie. Byle nie „Lead #4".
                    name = lead.customerName?.takeIf { it.isNotBlank() } ?: lead.contactIdentifier,
                    vehicle = listOfNotNull(lead.vehicleBrand, lead.vehicleModel)
                        .joinToString(" ")
                        .takeIf { it.isNotBlank() },
                    value = lead.estimatedValue,
                    waitingDays = ChronoUnit.DAYS.between(since, now).coerceAtLeast(0).toInt()
                )
            }
        )
    }

    // ── Wyciek pieniędzy ───────────────────────────────────────────────────

    /**
     * Powody straty przeliczone na złotówki.
     *
     * „Nikt nie odpisał" ma pierwszeństwo przed powodem wpisanym ręcznie. Jeżeli na
     * zapytanie nigdy nie poszła odpowiedź, to jest prawdziwy powód porażki — bez
     * względu na to, co ktoś zaznaczył tydzień później zamykając lead. To jest
     * jedyna pozycja tej listy, którą da się naprawić w tym tygodniu i za darmo,
     * więc ma być widoczna jako osobna, a nie rozpuszczona w „inne".
     */
    private fun leaks(leads: List<LeadEntity>): List<LeakDto> {
        val lost = leads.filter { it.status in LOST_STATUSES }
        if (lost.isEmpty()) return emptyList()

        val (silent, answered) = lost.partition { it.firstResponseAt == null }
        val fromReasons = answered
            .groupBy { it.lostReasonCode }
            .map { (reason, group) ->
                LeakDto(
                    code = reason?.name ?: "UNKNOWN",
                    label = reason?.label ?: "Bez podanego powodu",
                    value = group.sumOf { it.estimatedValue },
                    count = group.size
                )
            }

        val fromSilence = silent.takeIf { it.isNotEmpty() }?.let {
            LeakDto("NO_REPLY", "Nikt nie odpisał", it.sumOf { lead -> lead.estimatedValue }, it.size)
        }

        return (fromReasons + listOfNotNull(fromSilence))
            .filter { it.value > 0 || it.count > 0 }
            .sortedByDescending { it.value }
    }

    /**
     * Ile pieniędzy zamieniło się w rezerwacje od poniedziałku.
     *
     * Domknięcie pętli: nagroda za to, co użytkownik zrobił po ostatniej wizycie na
     * tym ekranie. Bez tego zdania widok tylko wymaga i nigdy nie kwituje.
     */
    private fun confirmedValueThisWeek(
        leads: List<LeadEntity>,
        firstDecisionAt: Map<UUID, Instant>
    ): Long {
        val mondayStart = LocalDate.now(DateRangeFilter.ZONE)
            .minusDays((LocalDate.now(DateRangeFilter.ZONE).dayOfWeek.value - 1).toLong())
            .atStartOfDay(DateRangeFilter.ZONE)
            .toInstant()
        val valueByLead = leads.associate { it.id to it.estimatedValue }
        return firstDecisionAt
            .filter { (_, decided) -> !decided.isBefore(mondayStart) }
            .mapNotNull { (leadId, _) -> valueByLead[leadId] }
            .sum()
    }

    // ── Kanały ─────────────────────────────────────────────────────────────

    private fun bySource(leads: List<LeadEntity>): List<SourceStatDto> =
        leads
            .groupBy { it.source }
            .map { (source, group) ->
                val won = group.count { it.status == LeadStatus.COMPLETED }
                val groupClosed = group.count { it.status in CLOSED_STATUSES }
                SourceStatDto(source.name, group.size, won, groupClosed, ratio(won, groupClosed))
            }
            .sortedByDescending { it.count }

    // ── Narzędzia ──────────────────────────────────────────────────────────

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator

    private fun Instant.localDate(): LocalDate = atZone(DateRangeFilter.ZONE).toLocalDate()

    private fun List<Long>.median(): Long? =
        if (isEmpty()) null else sorted()[size / 2]

    private companion object {
        val OPEN_STATUSES = setOf(LeadStatus.NEW, LeadStatus.IN_PROGRESS, LeadStatus.CONFIRMED)
        val LOST_STATUSES = setOf(LeadStatus.LOST, LeadStatus.NO_SHOW)
        val CLOSED_STATUSES = setOf(LeadStatus.COMPLETED, LeadStatus.LOST, LeadStatus.NO_SHOW)

        val RESPONSE_BUCKET_ORDER = listOf("UNDER_1H", "UNDER_4H", "UNDER_24H", "OVER_24H", "NO_REPLY")
        val FAST_KEYS = setOf("UNDER_1H", "UNDER_4H", "UNDER_24H")
        val SLOW_KEYS = setOf("OVER_24H", "NO_REPLY")

        /** Poniżej tylu rozstrzygniętych leadów po każdej stronie nie ma o czym mówić. */
        const val MIN_SAMPLE = 8

        /** Dziesięć punktów procentowych — mniej mieści się w przypadku. */
        const val RELATION_THRESHOLD = 0.10

        const val MIN_OUTLIER_SAMPLE = 5
        const val OUTLIER_THRESHOLD = 0.20
        const val MAX_OUTLIERS = 4

        /** Ponad kwartał liczymy miesiącami, żeby na wykresie było widać kształt. */
        const val MONTHLY_THRESHOLD_DAYS = 120L

        /** Tyle wierszy macierzy da się jeszcze ogarnąć wzrokiem; reszta idzie w „Pozostałe". */
        const val MAX_MATRIX_ROWS = 6

        /** Najdroższe na górze; usługi bez ani jednej wyceny na sam dół. */
        val BY_VALUE_DESC = compareByDescending<WeekdayMatrixRowDto> { it.averageValue ?: -1L }
            .thenByDescending { it.total }
    }
}
