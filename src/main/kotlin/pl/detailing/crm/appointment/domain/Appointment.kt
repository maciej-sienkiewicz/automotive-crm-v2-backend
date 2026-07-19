package pl.detailing.crm.appointment.domain

import pl.detailing.crm.appointment.recurrence.domain.RecurrenceSeriesId
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Price adjustment types for service line items
 */
enum class AdjustmentType {
    PERCENT,        // Percentage discount/markup on base price
    FIXED_NET,      // Fixed amount added to net price
    FIXED_GROSS,    // Fixed amount added to gross price
    SET_NET,        // Override net price completely
    SET_GROSS;      // Override gross price completely

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
    val adjustmentValue: Long, // In cents for FIXED_*, as integer for PERCENT, or final price for SET_*
    val finalPriceNet: Money,
    val finalPriceGross: Money,
    val customNote: String?
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
         * [basePriceGross] — the catalog's stored gross (exact, user-entered). When the
         * price flows gross-side (SET_GROSS / FIXED_GROSS / no-op adjustment on a gross
         * base) the exact gross is preserved instead of being re-derived from net, so
         * user-entered gross prices like 201.00 don't drift to 200.99.
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
            val finalNet = calculateFinalNet(basePriceNet, vatRate, adjustmentType, adjustmentValue)
            val finalGross = calculateFinalGross(finalNet, basePriceNet, vatRate, adjustmentType, adjustmentValue, basePriceGross)

            return AppointmentLineItem(
                serviceId = serviceId,
                serviceName = serviceName,
                basePriceNet = basePriceNet,
                vatRate = vatRate,
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValue,
                finalPriceNet = finalNet,
                finalPriceGross = finalGross,
                customNote = customNote
            )
        }

        /**
         * Price calculation engine - applies adjustment based on type
         */
        private fun calculateFinalNet(
            basePriceNet: Money,
            vatRate: VatRate,
            adjustmentType: AdjustmentType,
            adjustmentValue: Long
        ): Money {
            return when (adjustmentType) {
                AdjustmentType.PERCENT -> {
                    // adjustmentValue is in basis points (hundredths of percent)
                    // e.g., -4919 for -49.19% discount, +2050 for +20.50% markup
                    val multiplier = 1.0 + (adjustmentValue.toDouble() / 10000.0)
                    val calculatedNet = (basePriceNet.amountInCents * multiplier).toLong()
                    Money(calculatedNet.coerceAtLeast(0))
                }
                AdjustmentType.FIXED_NET -> {
                    // adjustmentValue is fixed amount in cents to add/subtract from net
                    val calculatedNet = basePriceNet.amountInCents + adjustmentValue
                    Money(calculatedNet.coerceAtLeast(0))
                }
                AdjustmentType.FIXED_GROSS -> {
                    // adjustmentValue is fixed amount to add/subtract from gross, recalculate net
                    val baseGross = vatRate.calculateGrossAmount(basePriceNet)
                    val newGross = (baseGross.amountInCents + adjustmentValue).coerceAtLeast(0)
                    // Calculate net from gross: net = gross / (1 + vatRate/100)
                    // For VAT_ZW (rate=-1) gross == net, so multiplier is 1.0
                    val vatMultiplier = if (vatRate == VatRate.VAT_ZW) 1.0 else 1.0 + (vatRate.rate.toDouble() / 100.0)
                    val calculatedNet = (newGross / vatMultiplier).toLong()
                    Money(calculatedNet.coerceAtLeast(0))
                }
                AdjustmentType.SET_NET -> {
                    // adjustmentValue is the final net price
                    Money(adjustmentValue.coerceAtLeast(0))
                }
                AdjustmentType.SET_GROSS -> {
                    // adjustmentValue is the final gross price, calculate net from it
                    // For VAT_ZW (rate=-1) gross == net, so multiplier is 1.0
                    val vatMultiplier = if (vatRate == VatRate.VAT_ZW) 1.0 else 1.0 + (vatRate.rate.toDouble() / 100.0)
                    val calculatedNet = (adjustmentValue / vatMultiplier).toLong()
                    Money(calculatedNet.coerceAtLeast(0))
                }
            }
        }

        /**
         * Final gross: preserved exactly whenever the price flows gross-side,
         * otherwise derived from the final net (VAT "od sta").
         */
        private fun calculateFinalGross(
            finalNet: Money,
            basePriceNet: Money,
            vatRate: VatRate,
            adjustmentType: AdjustmentType,
            adjustmentValue: Long,
            basePriceGross: Money?
        ): Money {
            val isNoOpAdjustment = when (adjustmentType) {
                AdjustmentType.PERCENT, AdjustmentType.FIXED_NET, AdjustmentType.FIXED_GROSS -> adjustmentValue == 0L
                AdjustmentType.SET_NET, AdjustmentType.SET_GROSS -> false
            }
            return when {
                // Explicit gross target — keep it to the grosz
                adjustmentType == AdjustmentType.SET_GROSS ->
                    Money(adjustmentValue.coerceAtLeast(0))
                // Gross-side discount from a known exact base gross
                adjustmentType == AdjustmentType.FIXED_GROSS && basePriceGross != null ->
                    Money((basePriceGross.amountInCents + adjustmentValue).coerceAtLeast(0))
                // No adjustment at all — the catalog's stored gross IS the final gross
                isNoOpAdjustment && basePriceGross != null && finalNet.amountInCents == basePriceNet.amountInCents ->
                    basePriceGross
                else -> vatRate.calculateGrossAmount(finalNet)
            }
        }
    }
}
