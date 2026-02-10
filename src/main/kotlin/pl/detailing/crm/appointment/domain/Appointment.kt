package pl.detailing.crm.appointment.domain

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
    SET_GROSS       // Override gross price completely
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
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
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
        // Validate financial integrity: gross = net + vat
        val expectedGross = vatRate.calculateGrossAmount(finalPriceNet)
        require(finalPriceGross.amountInCents == expectedGross.amountInCents) {
            "Financial integrity violation: finalPriceGross ($finalPriceGross) does not match " +
                "calculated gross from net ($expectedGross)"
        }
    }

    companion object {
        /**
         * Create a line item by applying price adjustment to base price
         */
        fun create(
            serviceId: ServiceId?,
            serviceName: String,
            basePriceNet: Money,
            vatRate: VatRate,
            adjustmentType: AdjustmentType,
            adjustmentValue: Long,
            customNote: String?
        ): AppointmentLineItem {
            val finalNet = calculateFinalNet(basePriceNet, vatRate, adjustmentType, adjustmentValue)
            val finalGross = vatRate.calculateGrossAmount(finalNet)

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
                    val vatMultiplier = 1.0 + (vatRate.rate.toDouble() / 100.0)
                    val calculatedNet = (newGross / vatMultiplier).toLong()
                    Money(calculatedNet.coerceAtLeast(0))
                }
                AdjustmentType.SET_NET -> {
                    // adjustmentValue is the final net price
                    Money(adjustmentValue.coerceAtLeast(0))
                }
                AdjustmentType.SET_GROSS -> {
                    // adjustmentValue is the final gross price, calculate net from it
                    val vatMultiplier = 1.0 + (vatRate.rate.toDouble() / 100.0)
                    val calculatedNet = (adjustmentValue / vatMultiplier).toLong()
                    Money(calculatedNet.coerceAtLeast(0))
                }
            }
        }
    }
}
