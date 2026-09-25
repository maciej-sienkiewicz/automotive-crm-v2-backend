package pl.detailing.crm.ownerreport.domain

import java.time.Instant

/**
 * Raport właściciela: co się wydarzyło w firmie w okresie i na czym studio stoi.
 *
 * Wszystkie kwoty w groszach. Każda jest SUMĄ kwot zapisanych w bazie (brutto
 * pozycji wizyty, brutto usługi zlecenia zbiorczego, brutto propozycji upsellu),
 * nigdy brutto odtworzonym z netto — CLAUDE.md §1. Raport, który pokaże 1900,01 zł
 * tam, gdzie klient zapłacił 1900,00 zł, traci zaufanie do wszystkich innych liczb.
 */
data class OwnerReport(
    val studioName: String,
    val studioAddress: String?,
    val logoPng: ByteArray?,
    val period: ReportPeriod,
    val current: PeriodMetrics,
    val previous: PeriodMetrics,
    /** Stan „na dziś", bez porównania: tego nie da się policzyć wstecz. */
    val snapshot: SnapshotMetrics,
    val generatedAt: Instant
)

/** Liczby, które da się policzyć dla dowolnego okresu — więc i dla poprzedniego. */
data class PeriodMetrics(
    /** Wizyty, w których prace ruszyły w okresie (DRAFT → IN_PROGRESS, `started_at`). */
    val visitsStarted: Int,
    /** Rezerwacje utworzone w okresie; seria cykliczna to jedna rezerwacja. */
    val reservationsCreated: Int,
    val closed: ClosedVisits,
    /** Koszty netto z dokumentów wystawionych w okresie (opłacone i nieopłacone). */
    val costsNetCents: Long,
    val emails: EmailMetrics,
    val upsell: UpsellMetrics,
    /** Rezerwacje/wizyty, do których klient dostał link do Karty Wizyty (e-mail lub SMS). */
    val visitCardsSent: Int,
    val batch: BatchMetrics,
    /** Null: studio nie wskazało własnego profilu na Instagramie. */
    val instagram: InstagramMetrics?
)

/** Wizyty zamknięte = wydane klientowi (COMPLETED, `pickup_date` w okresie). */
data class ClosedVisits(
    val count: Int,
    val grossCents: Long,
    val netCents: Long
) {
    /** Średnia wartość wizyty brutto; null, gdy nie zamknięto żadnej. */
    val averageGrossCents: Long?
        get() = if (count == 0) null else Math.floorDiv(grossCents + count / 2, count.toLong())
}

data class EmailMetrics(
    /** Napisane przez zespół ze skrzynki studia (także poza CRM — synchronizacja folderu Wysłane). */
    val sentByTeam: Int,
    /** Wysłane automatycznie przez CRM: powiadomienia o wizycie, karty wizyt, kampanie. */
    val sentAutomated: Int,
    val replies: ReplyTimes
)

/**
 * Czas odpowiedzi na maile klientów.
 *
 * Jednostką jest „tura" klienta: pierwsza wiadomość przychodząca po naszej odpowiedzi
 * (albo otwierająca rozmowę). Trzy maile klienta pod rząd to jedno czekanie na nas,
 * nie trzy. Czas liczony zegarowo — noc i weekend się wliczają, bo klient też na nie czeka.
 */
data class ReplyTimes(
    /** Tury klientów rozpoczęte w okresie. */
    val inquiries: Int,
    val answered: Int,
    val answeredWithinHour: Int,
    /** Mediana czasu do odpowiedzi w minutach; null, gdy nie ma żadnej odpowiedzi. */
    val medianMinutes: Long?
) {
    val unanswered: Int get() = inquiries - answered

    companion object {
        val EMPTY = ReplyTimes(0, 0, 0, null)

        /** @param waits czas czekania każdej tury w minutach; null = bez odpowiedzi. */
        fun of(waits: List<Long?>): ReplyTimes {
            val answered = waits.filterNotNull().sorted()
            val median = when {
                answered.isEmpty() -> null
                answered.size % 2 == 1 -> answered[answered.size / 2]
                else -> (answered[answered.size / 2 - 1] + answered[answered.size / 2]) / 2
            }
            return ReplyTimes(
                inquiries = waits.size,
                answered = answered.size,
                answeredWithinHour = answered.count { it <= 60 },
                medianMinutes = median
            )
        }
    }
}

data class UpsellMetrics(
    /** Propozycje dodatkowych usług przedstawione klientom (każda usługa osobno). */
    val suggested: Int,
    val suggestedGrossCents: Long,
    /** Ile wizyt/rezerwacji dostało co najmniej jedną propozycję. */
    val visitsWithSuggestions: Int,
    /** Propozycje potwierdzone przez klienta w okresie. */
    val accepted: Int,
    val acceptedGrossCents: Long
)

data class BatchMetrics(
    /** Auta obsłużone w ramach zleceń zbiorczych (data usługi w okresie). */
    val vehicles: Int,
    val grossCents: Long,
    val contractors: Int
)

data class InstagramMetrics(
    val posts: Int,
    val likes: Long,
    val comments: Long
)

data class SnapshotMetrics(
    /** Zlecenia zbiorcze wykonane, a jeszcze nierozliczone z kontrahentem. */
    val batchUnsettledVehicles: Int,
    val batchUnsettledGrossCents: Long,
    /** Null: studio nie ustawiło rejonu w monitoringu reklam. */
    val competitors: CompetitorMetrics?
)

/**
 * Konkurencja reklamująca się w rejonie studia (Biblioteka reklam Meta).
 *
 * Obraz aktualny, nie historyczny: cache trzyma tylko reklamy, które wciąż się
 * wyświetlają, więc „ruszyły w okresie" znaczy „ruszyły w okresie i nadal trwają".
 */
data class CompetitorMetrics(
    val advertisers: Int,
    val activeAds: Int,
    val campaignsStartedInPeriod: Int,
    /** Firmy, które zaczęły się reklamować w rejonie w tym okresie. */
    val newAdvertisers: List<String>,
    /** Najwięksi reklamodawcy: nazwa → liczba aktywnych reklam. */
    val top: List<Pair<String, Int>>
)
