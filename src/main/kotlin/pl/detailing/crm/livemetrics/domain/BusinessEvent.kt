package pl.detailing.crm.livemetrics.domain

import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Rodzaje zdarzeń biznesowych śledzonych w czasie rzeczywistym.
 *
 * Każdy typ ma stały, mały zbiór wymiarów ([dimensions]) — to jedyne etykiety, po
 * których liczniki są rozbijane na pod-serie (np. `VISIT_CREATED:FROM_RESERVATION`).
 * Zbiór jest zamknięty z założenia: wymiar o nieograniczonej kardynalności (id
 * klienta, nazwa pliku) trafia wyłącznie do [BusinessEvent.attributes], czyli do
 * strumienia zdarzeń, a nigdy do kluczy liczników w Redisie.
 */
enum class BusinessEventType(
    /** Nazwa wymiaru, po którym powstają pod-serie; `null` = typ bez wymiaru. */
    val dimension: String?,
    /** Dozwolone wartości wymiaru. */
    val dimensions: Set<String>,
    /** Etykieta czytelna dla człowieka (dashboardy). */
    val label: String
) {
    /** Użytkownik utworzył rezerwację (Appointment) — ręcznie, z leada lub jako serię. */
    RESERVATION_CREATED(null, emptySet(), "Rezerwacje"),

    /**
     * Powstała wizyta. Wymiar `origin` rozróżnia wizytę utworzoną bezpośrednio
     * (walk-in „z palca”, bez wcześniejszej rezerwacji) od wizyty będącej
     * przekształceniem istniejącej rezerwacji.
     */
    VISIT_CREATED("origin", VisitOrigin.entries.map { it.name }.toSet(), "Wizyty"),

    /** Do cennika/katalogu tenanta dodano nową usługę (lub pakiet usług). */
    SERVICE_CREATED("kind", ServiceKind.entries.map { it.name }.toSet(), "Nowe usługi"),

    /** Udany upload zdjęcia; wymiar `target` mówi, do czego zdjęcie przypięto. */
    PHOTO_UPLOADED("target", PhotoTarget.entries.map { it.name }.toSet(), "Zdjęcia"),

    /** Powstał nowy rekord w ogólnej historii aktywności (audit log) tenanta. */
    ACTIVITY_LOGGED(null, emptySet(), "Aktywność");

    /** Nazwa serii bazowej (bez wymiaru). */
    val series: String get() = name

    /** Nazwa pod-serii dla danej wartości wymiaru, np. `VISIT_CREATED:DIRECT`. */
    fun subSeries(dimensionValue: String): String = "$name:$dimensionValue"

    /** Wszystkie serie, jakie ten typ może wytworzyć: bazowa + po jednej na wartość wymiaru. */
    fun allSeries(): List<String> = listOf(series) + dimensions.map { subSeries(it) }

    companion object {
        /** Pełna lista serii dla dashboardów — także tych, które nie miały jeszcze zdarzeń. */
        fun allKnownSeries(): List<String> = entries.flatMap { it.allSeries() }
    }
}

enum class VisitOrigin {
    /** Wizyta utworzona bezpośrednio — bez wcześniejszej rezerwacji w kalendarzu. */
    DIRECT,

    /** Wizyta powstała z przekształcenia / potwierdzenia istniejącej rezerwacji. */
    FROM_RESERVATION
}

enum class ServiceKind { SERVICE, PACKAGE }

enum class PhotoTarget { VISIT, VEHICLE, BATCH_ORDER, CHECKIN }

/**
 * Pojedyncze zdarzenie biznesowe — niezmienny fakt: „u tenanta X o czasie T stało się Y”.
 *
 * [dimensionValue] musi należeć do [BusinessEventType.dimensions] (albo być `null`,
 * gdy typ nie ma wymiaru); [attributes] to dowolny, płaski kontekst wysyłany dalej
 * strumieniem (id encji, nazwa, kto) — nigdy nie trafia do kluczy liczników.
 */
data class BusinessEvent(
    val tenantId: StudioId,
    val type: BusinessEventType,
    val occurredAt: Instant = Instant.now(),
    val dimensionValue: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val id: UUID = UUID.randomUUID()
) {
    init {
        if (type.dimension == null) {
            require(dimensionValue == null) { "${type.name} has no dimension, got '$dimensionValue'" }
        } else {
            require(dimensionValue != null && dimensionValue in type.dimensions) {
                "${type.name}.${type.dimension} must be one of ${type.dimensions}, got '$dimensionValue'"
            }
        }
    }

    /** Serie, których liczniki to zdarzenie inkrementuje: bazowa i (jeśli jest) pod-seria. */
    fun series(): List<String> =
        if (dimensionValue == null) listOf(type.series) else listOf(type.series, type.subSeries(dimensionValue))
}
