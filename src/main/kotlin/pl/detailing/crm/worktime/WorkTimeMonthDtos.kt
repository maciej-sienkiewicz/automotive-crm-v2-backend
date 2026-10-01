package pl.detailing.crm.worktime

import com.fasterxml.jackson.annotation.JsonProperty

/*
 * Kontrakt listy miesięcznej (docs/api-worktime-months.md). Nazwy pól są częścią API —
 * zmiana tutaj to zmiana we froncie.
 *
 * Imiona i nazwiska pracowników nie są oznaczone @Pii: to dane kadrowe, którymi zarządza
 * rodzina uprawnień EMPLOYEES (jak w pl.detailing.crm.employee), a nie dane klientów.
 * Maska dla menedżera bez CUSTOMERS_VIEW zrobiłaby z listy kart listę gwiazdek.
 */

/** Stan karty; [NOT_STARTED] jest wyliczany — nic nie wpisano i karta nie była złożona. */
enum class CardStatus { NOT_STARTED, DRAFT, SUBMITTED, RETURNED, APPROVED }

enum class MonthStage { COLLECTING, REVIEWING, READY_TO_SIGN, SIGNED, NEEDS_RESIGN }

data class MonthCardRowResponse(
    val userId: String,
    val employeeId: String?,
    val name: String,
    val status: CardStatus,
    val totalMinutes: Int,
    val expectedMinutes: Int,
    val missingWorkingDays: Int,
    val overtimeMinutes: Int,
    val leaveWorkingDays: Int,
    val submittedAt: String?,
    val approvedAt: String?,
    val approvedByName: String?,
    val returnNote: String?,
    val canDecide: Boolean,
    val remindedAt: String?
)

data class MonthSheetResponse(
    val id: String,
    val status: String,
    val outdated: Boolean,
    val generatedAt: String,
    val approvedAt: String?,
    val approvedByName: String?,
    val excludedNames: List<String>
)

data class MonthCountsResponse(
    val total: Int,
    val notSubmitted: Int,
    val submitted: Int,
    val returned: Int,
    val approved: Int
)

data class MonthOverviewResponse(
    val period: String,
    val label: String,
    val workingDays: Int,
    val stage: MonthStage,
    val counts: MonthCountsResponse,
    val employees: List<MonthCardRowResponse>,
    val sheet: MonthSheetResponse?,
    val sheetHistory: List<MonthSheetResponse>
)

data class CardLeaveResponse(
    val type: String,
    val label: String
)

data class CardDayResponse(
    val date: String,
    val minutes: Int?,
    val note: String?,
    @get:JsonProperty("isWorkingDay")
    val isWorkingDay: Boolean,
    val holidayName: String?,
    val leave: CardLeaveResponse?,
    val missing: Boolean
)

/** `CardDetail extends MonthCardRow` z kontraktu — pola wiersza plus dni miesiąca. */
data class CardDetailResponse(
    val userId: String,
    val employeeId: String?,
    val name: String,
    val status: CardStatus,
    val totalMinutes: Int,
    val expectedMinutes: Int,
    val missingWorkingDays: Int,
    val overtimeMinutes: Int,
    val leaveWorkingDays: Int,
    val submittedAt: String?,
    val approvedAt: String?,
    val approvedByName: String?,
    val returnNote: String?,
    val canDecide: Boolean,
    val remindedAt: String?,
    val period: String,
    val label: String,
    val days: List<CardDayResponse>,
    val returnedAt: String?,
    val returnedByName: String?
)

data class UserIdsRequest(
    val userIds: List<String> = emptyList()
)

data class SkippedCardResponse(
    val userId: String,
    val reason: String
)

data class BulkApproveResponse(
    val approved: List<String>,
    val skipped: List<SkippedCardResponse>
)

data class RemindResponse(
    val reminded: List<String>,
    val skipped: List<SkippedCardResponse>
)

data class GenerateMonthSheetRequest(
    val allowIncomplete: Boolean = false
)

data class PendingCountResponse(
    val submittedCards: Int,
    val sheetsToSign: Int
)

/**
 * Lista obecności nie powstanie po cichu z niepełnego miesiąca: 409 niesie nazwiska osób
 * bez zatwierdzonej karty, żeby menedżer świadomie zdecydował, czy je pominąć.
 */
class IncompleteAttendanceSheetException(val names: List<String>) : RuntimeException(
    "Nie wszystkie karty są zatwierdzone. Bez zatwierdzonej karty: ${names.joinToString(", ")}."
)

/** Treść 409 dla [IncompleteAttendanceSheetException] — `{ message, field: null, names }`. */
data class IncompleteAttendanceSheetResponse(
    val error: String,
    val message: String,
    val timestamp: String,
    val code: String,
    val field: String?,
    val names: List<String>
)
