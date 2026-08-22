package pl.detailing.crm.leads.analytics

import java.time.Instant
import java.time.LocalDate

/**
 * Analityka leadów odpowiada na pytania, które właściciel studia zadaje sobie sam,
 * a nie na te, które da się policzyć.
 *
 * Każde pole tego DTO ma po drugiej stronie jedno zdanie w interfejsie („najwięcej
 * zapytań przychodzi w poniedziałki"). Jeżeli z liczby nie da się takiego zdania
 * ułożyć, liczba nie ma tu czego szukać: wykres, który wymaga analizy, dokłada
 * właścicielowi pracy zamiast ją zdejmować.
 *
 * Wszystko liczy się wprost z leadów okna — bez osobnego magazynu analitycznego,
 * który mógłby się rozjechać z prawdą.
 */
data class LeadAnalyticsDto(
    val from: Instant,
    val to: Instant,
    /** Liczba zapytań, które wpłynęły w okresie. */
    val totalCreated: Int,
    /** Rozkład bieżących statusów leadów z okresu. */
    val byStatus: Map<String, Int>,
    /** Skuteczność: wygrane / wszystkie rozstrzygnięte (wygrane + przegrane + no-show). */
    val conversionRate: Double?,
    /** Wartość wygranych leadów (grosze). */
    val wonValue: Long,
    /** Wartość przegranych leadów (grosze) — pieniądze, które przeszły obok. */
    val lostValue: Long,
    /** Wartość leadów wciąż otwartych (grosze). */
    val pipelineValue: Long,
    /** O co pytają: rozkład kategorii z podziałem wygrane/przegrane. */
    val categories: List<CategoryStatDto>,
    /** Dlaczego przegrywamy: rozkład powodów w leadach LOST. */
    val lostReasons: List<LostReasonStatDto>,
    /** Mediana czasu pierwszej odpowiedzi w minutach. */
    val medianFirstResponseMinutes: Long?,
    /** Mediana liczby dni od zapytania do rezerwacji — po ilu dniach warto odpuścić. */
    val medianDaysToDecision: Long?,
    /** Kiedy przychodzą zapytania: 1 = poniedziałek … 7 = niedziela. */
    val inquiriesByWeekday: List<WeekdayStatDto>,
    /** Kiedy klienci się decydują (przejście na REZERWACJĘ), ta sama skala dni. */
    val decisionsByWeekday: List<WeekdayStatDto>,
    /** Kiedy w miesiącu przychodzą zapytania: dni 1–31. */
    val inquiriesByMonthDay: List<MonthDayStatDto>,
    /** Czy szybka odpowiedź podnosi skuteczność — przedziały plus werdykt. */
    val responseImpact: ResponseImpactDto,
    /** Marki i modele, które wyraźnie odstają od średniej skuteczności. */
    val vehicleOutliers: List<VehicleOutlierDto>,
    /** Trend: zapytania i skuteczność w kolejnych tygodniach albo miesiącach. */
    val timeline: List<TimelinePointDto>,
    /** Kanał, którym przyszło zapytanie, i jego skuteczność. */
    val bySource: List<SourceStatDto>,

    // ── Rachunek pieniędzy: to, na czym stoi cały widok ────────────────────

    /** Pieniądze czekające na odpowiedź studia — stan bieżący, poza oknem raportu. */
    val awaiting: AwaitingWorkDto,
    /** Gdzie wyciekły pieniądze: powody straty w złotówkach, z „nikt nie odpisał" włącznie. */
    val leaks: List<LeakDto>,
    /** Wartość wygranych w poprzednim okresie tej samej długości — jedyny punkt odniesienia. */
    val wonValuePrevious: Long,
    /** Ile pieniędzy zamieniło się w rezerwacje od poniedziałku — domknięcie pętli. */
    val confirmedValueThisWeek: Long
)

data class CategoryStatDto(
    val code: String?,
    val label: String,
    val count: Int,
    val completed: Int,
    /** Przegrane i niedoszłe — druga strona tego samego rachunku. */
    val lost: Int,
    val conversionRate: Double?
)

data class LostReasonStatDto(
    val code: String,
    val label: String,
    val count: Int,
    val share: Double
)

/** [weekday] w skali ISO: 1 = poniedziałek, 7 = niedziela. Zawsze komplet siedmiu. */
data class WeekdayStatDto(val weekday: Int, val count: Int)

/** [day] to dzień miesiąca 1–31. Zawsze komplet, także z zerami. */
data class MonthDayStatDto(val day: Int, val count: Int)

data class SourceStatDto(
    val source: String,
    val count: Int,
    val won: Int,
    val closed: Int,
    val winRate: Double?
)

/**
 * [key]: UNDER_1H | UNDER_4H | UNDER_24H | OVER_24H | NO_REPLY.
 * Nazwy przedziałów zostają po stronie interfejsu — backend nie zna języka okna.
 */
data class ResponseBucketDto(
    val key: String,
    val count: Int,
    val won: Int,
    val closed: Int,
    val winRate: Double?
)

/**
 * [verdict]: FASTER_WINS | NO_RELATION | NOT_ENOUGH_DATA.
 *
 * Werdykt liczy się tutaj, a nie w przeglądarce, bo to jest ocena danych, a nie
 * sposób ich pokazania. „Nie wykryto zależności" jest pełnoprawną odpowiedzią
 * i musi paść wprost — inaczej każdy przypadkowy garb na wykresie zostanie
 * odczytany jako prawidłowość.
 */
data class ResponseImpactDto(
    val buckets: List<ResponseBucketDto>,
    val verdict: String,
    /** Skuteczność przy odpowiedzi w dobę i po dobie — liczby, na których stoi werdykt. */
    val fastWinRate: Double?,
    val slowWinRate: Double?
)

/**
 * [direction]: ABOVE (wygrywamy wyraźnie częściej) | BELOW (przegrywamy wyraźnie częściej).
 *
 * Lista jest krótka z zamysłu: to ma być „popatrz na to", a nie tabela do przejrzenia.
 */
data class VehicleOutlierDto(
    val label: String,
    val count: Int,
    val won: Int,
    val closed: Int,
    val winRate: Double,
    val direction: String
)

/** [periodStart] to poniedziałek tygodnia albo pierwszy dzień miesiąca. */
data class TimelinePointDto(
    val periodStart: LocalDate,
    val created: Int,
    val won: Int,
    val lost: Int,
    val winRate: Double?
)

/**
 * Pieniądze, które czekają na odpowiedź studia — stan BIEŻĄCY, nie okno raportu.
 *
 * Świadomie poza zakresem dat: zaległa odpowiedź nie przestaje być zaległa dlatego,
 * że ktoś przełączył widok na „ostatnie 30 dni". Rozmowa sprzed czterdziestu dni,
 * w której klient wciąż czeka, jest tą najpilniejszą; ukrycie jej za filtrem okna
 * byłoby ukryciem największego długu.
 */
data class AwaitingWorkDto(
    val value: Long,
    val count: Int,
    /** Najdłużej czekający — z nazwiskiem i autem, żeby to był konkretny człowiek. */
    val oldest: AwaitingLeadDto?
)

data class AwaitingLeadDto(
    val leadId: String,
    val name: String,
    val vehicle: String?,
    val value: Long,
    val waitingDays: Int
)

/**
 * Wyciek: ile pieniędzy uciekło z danego powodu. W złotówkach, nie w sztukach —
 * sześć przegranych praniach tapicerki i sześć przegranych powłok ceramicznych to
 * ta sama liczba i zupełnie inna strata.
 *
 * [code] to kod powodu albo NO_REPLY dla zapytań, na które nikt nigdy nie odpisał.
 */
data class LeakDto(
    val code: String,
    val label: String,
    val value: Long,
    val count: Int
)
