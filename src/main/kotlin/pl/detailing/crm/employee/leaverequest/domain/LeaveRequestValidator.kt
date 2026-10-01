package pl.detailing.crm.employee.leaverequest.domain

import org.springframework.stereotype.Component
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestPdfRenderer
import pl.detailing.crm.shared.ValidationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Treść wniosku po odczytaniu z żądania — typy już sprawdzone, teksty przycięte. */
data class LeaveRequestDraft(
    val leaveType: LeaveType,
    val onDemand: Boolean,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String?
)

/**
 * Reguły wniosku urlopowego z kontraktu (`docs/api-leave-requests.md`). Każdy błąd niesie
 * nazwę pola, bo front pokazuje go przy polu, a nie w toaście — „data wstecz" albo
 * „więcej niż 4 dni na żądanie" to rzeczy, które pracownik poprawia w tym samym kroku.
 *
 * Reguły zależne od innych wniosków ([checkAgainstExisting]) muszą biec pod blokadą
 * wiersza pracownika (`EmployeeRepository.lockForUpdate`) — inaczej dwa równoległe
 * złożenia na ten sam termin oba widziałyby „wolne".
 */
@Component
class LeaveRequestValidator(
    private val leaveRequestRepository: LeaveRequestRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository
) {
    companion object {
        /** Art. 167² KP: urlop na żądanie — najwyżej 4 dni w roku kalendarzowym. */
        const val ON_DEMAND_DAYS_PER_YEAR = 4

        /** Najdłuższy zakres jednego wniosku — dłuższy to pomyłka w dacie, nie urlop. */
        const val MAX_SPAN_DAYS = 366L

        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    /** Odczytuje i sprawdza pola żądania, które nie wymagają bazy. */
    fun parse(
        leaveType: String?,
        onDemand: Boolean,
        startDate: LocalDate?,
        endDate: LocalDate?,
        reason: String?
    ): LeaveRequestDraft {
        val type = leaveType?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            LeaveType.entries.firstOrNull { it.name == raw.uppercase() }
        } ?: throw ValidationException("Wybierz rodzaj urlopu", field = "leaveType")
        if (type !in RequestableLeaveTypes.ALL) {
            throw ValidationException(
                "Zwolnienia lekarskiego nie składa się wnioskiem — wpisuje je przełożony w nieobecnościach.",
                field = "leaveType"
            )
        }
        if (onDemand && type != LeaveType.ANNUAL) {
            throw ValidationException("Urlop na żądanie jest odmianą urlopu wypoczynkowego", field = "onDemand")
        }
        val start = startDate ?: throw ValidationException("Podaj datę rozpoczęcia urlopu", field = "startDate")
        val end = endDate ?: throw ValidationException("Podaj datę zakończenia urlopu", field = "endDate")
        if (end.isBefore(start)) {
            throw ValidationException("Data zakończenia urlopu nie może być wcześniejsza niż data rozpoczęcia", field = "endDate")
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_SPAN_DAYS) {
            throw ValidationException("Jeden wniosek może obejmować najwyżej rok", field = "endDate")
        }

        val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanReason != null && cleanReason.length > LeaveRequestPdfRenderer.MAX_REASON_LENGTH) {
            throw ValidationException(
                "Uzasadnienie może mieć najwyżej ${LeaveRequestPdfRenderer.MAX_REASON_LENGTH} znaków — tyle mieści się na wniosku",
                field = "reason"
            )
        }
        if (type == LeaveType.SPECIAL && cleanReason == null) {
            throw ValidationException("Podaj uzasadnienie — przy urlopie okolicznościowym jest wymagane", field = "reason")
        }
        return LeaveRequestDraft(type, onDemand, start, end, cleanReason)
    }

    /**
     * Termin względem dnia dzisiejszego i liczba dni roboczych. Zwraca dni robocze.
     *
     * Zwykły wniosek składa się z wyprzedzeniem — najpóźniej dzień przed urlopem. „Od dziś"
     * wolno tylko na żądanie, bo tego urlopu pracodawca udziela w terminie wskazanym przez
     * pracownika (kontrakt: „startDate w przeszłości, poza onDemand na dziś").
     */
    fun checkTerm(draft: LeaveRequestDraft, today: LocalDate): Int {
        if (draft.startDate.isBefore(today)) {
            throw ValidationException("Nie można złożyć wniosku o urlop z datą wsteczną", field = "startDate")
        }
        if (draft.startDate == today && !draft.onDemand) {
            throw ValidationException(
                "Urlop od dziś można wziąć tylko jako urlop na żądanie. Zwykły wniosek składa się najpóźniej dzień wcześniej.",
                field = "startDate"
            )
        }
        val workingDays = PolishHolidays.workingDaysBetween(draft.startDate, draft.endDate)
        if (workingDays == 0) {
            throw ValidationException("W wybranym terminie nie ma dni roboczych — same weekendy albo święta", field = "startDate")
        }
        return workingDays
    }

    /**
     * Kolizje z tym, co już zajmuje termin pracownika, i limit urlopu na żądanie.
     * Wymaga blokady wiersza pracownika w bieżącej transakcji.
     *
     * @param excludeId wniosek sprawdzany (przy złożeniu szkicu nie koliduje sam ze sobą)
     * @param onBehalf komunikat dla administratora wprowadzającego wniosek („pracownik ma"),
     *   a nie dla samego pracownika („masz") — reguły są te same
     */
    fun checkAgainstExisting(
        studioId: UUID,
        employeeId: UUID,
        excludeId: UUID,
        draft: LeaveRequestDraft,
        onBehalf: Boolean = false
    ) {
        val has = if (onBehalf) "pracownik ma" else "masz"
        leaveRequestRepository.findOverlappingOfEmployee(
            studioId, employeeId, LeaveRequestStatus.BLOCKING, draft.startDate, draft.endDate, excludeId
        ).firstOrNull()?.let { other ->
            val state = if (other.status == LeaveRequestStatus.APPROVED) "zatwierdzony" else "oczekuje na decyzję"
            throw ValidationException(
                "W tym terminie $has już wniosek ${other.number} (${range(other.startDate, other.endDate)}, $state)",
                field = "startDate"
            )
        }

        employeeLeaveRepository.findOverlappingOfEmployee(studioId, employeeId, draft.startDate, draft.endDate)
            .firstOrNull()?.let { leave ->
                val what = if (leave.leaveType == LeaveType.SICK) "zwolnienie lekarskie" else "urlop"
                throw ValidationException(
                    "W tym terminie $has już wpisaną nieobecność ($what ${range(leave.startDate, leave.endDate)})",
                    field = "startDate"
                )
            }

        if (draft.onDemand) checkOnDemandLimit(studioId, employeeId, excludeId, draft)
    }

    /**
     * Limit liczony osobno dla każdego roku, którego dotyka wniosek — urlop na żądanie
     * 30.12–02.01 zużywa dni z dwóch różnych pul.
     */
    private fun checkOnDemandLimit(studioId: UUID, employeeId: UUID, excludeId: UUID, draft: LeaveRequestDraft) {
        for (year in draft.startDate.year..draft.endDate.year) {
            val yearStart = LocalDate.of(year, 1, 1)
            val yearEnd = LocalDate.of(year, 12, 31)
            val requested = PolishHolidays.workingDaysBetween(maxOf(draft.startDate, yearStart), minOf(draft.endDate, yearEnd))
            if (requested == 0) continue
            val used = leaveRequestRepository.findOnDemandOfEmployee(
                studioId, employeeId, LeaveRequestStatus.BLOCKING, yearStart, yearEnd, excludeId
            ).sumOf { PolishHolidays.workingDaysBetween(maxOf(it.startDate, yearStart), minOf(it.endDate, yearEnd)) }
            if (used + requested > ON_DEMAND_DAYS_PER_YEAR) {
                throw ValidationException(
                    "Urlop na żądanie przysługuje w wymiarze $ON_DEMAND_DAYS_PER_YEAR dni w roku kalendarzowym. " +
                        "W $year wykorzystano lub zawnioskowano już ${days(used)}, ten wniosek to ${days(requested)}.",
                    field = "onDemand"
                )
            }
        }
    }

    private fun range(from: LocalDate, to: LocalDate): String =
        if (from == to) from.format(DATE) else "${from.format(DATE)}–${to.format(DATE)}"

    private fun days(n: Int): String = if (n == 1) "1 dzień" else "$n dni"
}
