package pl.detailing.crm.appointment.domain

import pl.detailing.crm.appointment.recurrence.domain.RecurrenceSeriesId
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.PriceCalculator
import java.time.Instant
import java.time.ZoneId

/**
 * Price adjustment types for service line items
 */
enum class AdjustmentType {
    PERCENT,        // Rabat/narzut procentowy od netta (basis points; ujemny = rabat)
    FIXED_NET,      // Rabat kwotowy od netta w groszach, ODEJMOWANY (v > 0 = rabat)
    FIXED_GROSS,    // Rabat kwotowy od brutto w groszach, ODEJMOWANY (v > 0 = rabat)
    SET_NET,        // Docelowa cena netto
    SET_GROSS;      // Docelowa cena brutto (zachowywana co do grosza)

    companion object {
        /**
         * Converts PERCENT adjustment value from API input to internal basis points representation.
         *
         * Input value semantics (signed convention per spec):
         * - Negative → discount  (e.g. -10.5 = 10.5% discount → -1050 bp)
         * - Positive → markup    (e.g.  +5.0 = 5% markup      → +500 bp)
         * - Zero     → no change (0 bp)
         */
        fun convertPercentValueToBasisPoints(value: Double): Long {
            return Math.round(value * 100)
        }
    }
}

/**
 * Appointment status lifecycle
 */
enum class AppointmentStatus {
    CREATED,
    ABANDONED,
    CANCELLED,
    CONVERTED
}

/**
 * Core appointment domain model
 */
data class Appointment(
    val id: AppointmentId,
    val studioId: StudioId,
    val customerId: CustomerId,
    val vehicleId: VehicleId?,
    val appointmentTitle: String?,
    val appointmentColorId: AppointmentColorId,
    val lineItems: List<AppointmentLineItem>,
    val schedule: AppointmentSchedule,
    val status: AppointmentStatus,
    val note: String?,
    val sendReminderSms: Boolean = false,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val recurrenceSeriesId: RecurrenceSeriesId? = null,
    val recurrenceIndex: Int? = null,
    val isDetached: Boolean = false
) {
    /**
     * Calculate total net amount across all line items
     */
    fun calculateTotalNet(): Money {
        return lineItems.fold(Money.ZERO) { acc, item -> acc.plus(item.finalPriceNet) }
    }

    /**
     * Calculate total gross amount across all line items
     */
    fun calculateTotalGross(): Money {
        return lineItems.fold(Money.ZERO) { acc, item -> acc.plus(item.finalPriceGross) }
    }

    /**
     * Calculate total VAT amount
     */
    fun calculateTotalVat(): Money {
        return calculateTotalGross().minus(calculateTotalNet())
    }
}

/**
 * Schedule information for an appointment
 */
data class AppointmentSchedule(
    val isAllDay: Boolean,
    val startDateTime: Instant,
    val endDateTime: Instant
) {
    init {
        require(endDateTime.isAfter(startDateTime)) {
            "End date/time must be after start date/time"
        }
    }

    /**
     * Check if this schedule overlaps with another
     */
    fun overlapsWith(other: AppointmentSchedule): Boolean {
        return !(endDateTime.isBefore(other.startDateTime) || startDateTime.isAfter(other.endDateTime))
    }

    companion object {
        private val STUDIO_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

        /**
         * Flaga „całodniowa", którą wolno zapisać: wizyta całodniowa trwa JEDEN dzień
         * (początek i koniec tego samego dnia czasu polskiego). Wizyta na kilka dni ma
         * zawsze godzinę rozpoczęcia i zakończenia, więc przy kilku dniach flaga spada,
         * a godziny zostają takie, jakie przyszły.
         *
         * Normalizujemy zamiast odrzucać: ekran edycji rezerwacji nie ma przełącznika
         * „całodniowa" i odsyłał flagę oryginału razem z przesuniętym końcem
         * (całodniowa 24.09 przeciągnięta do 28.09 zostawała całodniowa). Starszy front
         * w otwartej karcie wysyła tak nadal - zapis ma się udać, a stan być poprawny.
         */
        fun resolveAllDay(requested: Boolean, startDateTime: Instant, endDateTime: Instant): Boolean =
            requested && startDateTime.atZone(STUDIO_ZONE).toLocalDate() == endDateTime.atZone(STUDIO_ZONE).toLocalDate()

        /** Termin z żądania, z flagą całodniową tylko dla wizyty jednodniowej ([resolveAllDay]). */
        fun of(isAllDay: Boolean, startDateTime: Instant, endDateTime: Instant) = AppointmentSchedule(
            isAllDay = resolveAllDay(isAllDay, startDateTime, endDateTime),
            startDateTime = startDateTime,
            endDateTime = endDateTime,
        )
    }
}

/**
 * Individual service line item with price adjustment
 */
data class AppointmentLineItem(
    val serviceId: ServiceId?,
    val serviceName: String,
    val basePriceNet: Money,
    val vatRate: VatRate,
    val adjustmentType: AdjustmentType,
    val adjustmentValue: Long, // basis points for PERCENT; discount in grosz for FIXED_* (v > 0 = rabat); target price for SET_*
    val finalPriceNet: Money,
    val finalPriceGross: Money,
    val customNote: String?,
    /**
     * Dokładne brutto ceny bazowej wpisanej od strony brutto (albo z katalogu); `null` =
     * cena od strony netta. Musi przetrwać zapis rezerwacji, bo check-in przepisuje z niej
     * cenę na wizytę — odtworzone z netta brutto 1900,00 wraca jako 1900,01 (CLAUDE.md §1).
     */
    val basePriceGross: Money? = null
) {
    init {
        // Financial integrity: gross must correspond to net. A 1-grosz tolerance is
        // allowed because gross-entered prices (SET_GROSS / stored catalog gross) are
        // carried exactly — VAT "w stu" — and net→gross re-derivation can differ by
        // a single grosz of rounding (e.g. gross 201.00 → net 163.41 → derived 200.99).
        val expectedGross = vatRate.calculateGrossAmount(finalPriceNet)
        require(Math.abs(finalPriceGross.amountInCents - expectedGross.amountInCents) <= 1) {
            "Financial integrity violation: finalPriceGross ($finalPriceGross) does not match " +
                "calculated gross from net ($expectedGross)"
        }
    }

    companion object {
        /**
         * Create a line item by applying price adjustment to base price.
         *
         * Rezerwacja liczy dokładnie tym samym silnikiem co wizyta ([PriceCalculator]):
         * check-in przepisuje `adjustmentValue` z rezerwacji na pozycję wizyty, a każda
         * późniejsza edycja pozycji wizyty liczy ją [PriceCalculator]-em. Własny silnik
         * rezerwacji DODAWAŁ rabat kwotowy (front wysyła go jako wartość dodatnią), więc
         * „rabat 100 zł" zapisywał się jako narzut, a PERCENT/SET_GROSS obcinał netto
         * zamiast je zaokrąglić.
         *
         * [basePriceGross] — dokładne brutto ceny bazowej (wpisane od brutto albo z katalogu).
         * Gdy cena płynie od strony brutto (SET_GROSS / FIXED_GROSS / rabat zerowy), to brutto
         * przechodzi dokładnie, zamiast być odtwarzane z netta (1900,00 nie zjeżdża do 1900,01).
         *
         * Rabat większy niż cena daje 0 zamiast błędu walidacji — tak rezerwacja działała
         * zawsze, a zapisane wcześniej rezerwacje muszą dać się przyjąć na check-inie.
         */
        fun create(
            serviceId: ServiceId?,
            serviceName: String,
            basePriceNet: Money,
            vatRate: VatRate,
            adjustmentType: AdjustmentType,
            adjustmentValue: Long,
            customNote: String?,
            basePriceGross: Money? = null
        ): AppointmentLineItem {
            val finalNet = Money(
                PriceCalculator.adjustedNetCents(basePriceNet, vatRate, adjustmentType, adjustmentValue, basePriceGross)
                    .coerceAtLeast(0)
            )
            val finalGross = PriceCalculator.calculateFinalGross(
                finalNet, basePriceNet, vatRate, adjustmentType, adjustmentValue, basePriceGross
            )

            return AppointmentLineItem(
                serviceId = serviceId,
                serviceName = serviceName,
                basePriceNet = basePriceNet,
                vatRate = vatRate,
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValue,
                finalPriceNet = finalNet,
                finalPriceGross = finalGross,
                customNote = customNote,
                basePriceGross = basePriceGross
            )
        }
    }
}
