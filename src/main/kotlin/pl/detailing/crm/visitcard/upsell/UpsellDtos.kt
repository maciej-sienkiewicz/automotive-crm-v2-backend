package pl.detailing.crm.visitcard.upsell

import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellSuggestionStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import java.time.Instant

// ─── Employee (authenticated) API ─────────────────────────────────────────────

/**
 * Payload for attaching a suggested additional service to a visit.
 * Pricing is snapshotted from the catalog service; [adjustment] applies an
 * optional discount/markup, mirroring the semantics of visit service items
 * (PERCENT value is a human percentage, e.g. -10.5; monetary values are grosz).
 */
data class CreateUpsellSuggestionRequest(
    val serviceId: String,
    val adjustment: UpsellAdjustment? = null,
    val note: String? = null
)

data class UpsellAdjustment(
    val type: AdjustmentType,
    val value: Double
)

data class UpsellSuggestionResponse(
    val id: String,
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustmentType: AdjustmentType,
    /** PERCENT: basis points (negative = discount); other types: grosz. */
    val adjustmentValue: Long,
    val finalPriceNet: Long,
    val finalPriceGross: Long,
    /** Gross price before the discount — equals finalPriceGross when no discount. */
    val originalPriceGross: Long,
    val note: String?,
    val status: UpsellSuggestionStatus,
    val createdAt: Instant,
    val requestedAt: Instant?,
    val confirmedAt: Instant?
) {
    companion object {
        fun from(entity: VisitUpsellSuggestionEntity, originalPriceGross: Long): UpsellSuggestionResponse =
            UpsellSuggestionResponse(
                id = entity.id.toString(),
                serviceId = entity.serviceId.toString(),
                serviceName = entity.serviceName,
                basePriceNet = entity.basePriceNet,
                vatRate = entity.vatRate,
                adjustmentType = entity.adjustmentType,
                adjustmentValue = entity.adjustmentValue,
                finalPriceNet = entity.finalPriceNet,
                finalPriceGross = entity.finalPriceGross,
                originalPriceGross = originalPriceGross,
                note = entity.note,
                status = entity.status,
                createdAt = entity.createdAt,
                requestedAt = entity.requestedAt,
                confirmedAt = entity.confirmedAt
            )
    }
}

// ─── Public (tokenized Visit Card) API ────────────────────────────────────────

/** A single suggested service as shown to the customer on the Visit Card. */
data class VisitCardUpsellSuggestion(
    val id: String,
    val name: String,
    val note: String?,
    val priceNet: Long,     // grosz
    val priceGross: Long,   // grosz
    /** Gross price before the discount; null when the suggestion has no discount. */
    val originalPriceGross: Long?,
    val status: UpsellSuggestionStatus
)

/** Maps a suggestion to its customer-facing shape (no internal pricing internals). */
fun VisitUpsellSuggestionEntity.toPublicDto(): VisitCardUpsellSuggestion {
    val originalGross = pl.detailing.crm.shared.VatRate.fromInt(vatRate)
        .calculateGrossAmount(pl.detailing.crm.shared.Money.fromCents(basePriceNet))
        .amountInCents
    return VisitCardUpsellSuggestion(
        id = id.toString(),
        name = serviceName,
        note = note,
        priceNet = finalPriceNet,
        priceGross = finalPriceGross,
        originalPriceGross = originalGross.takeIf { it != finalPriceGross },
        status = status
    )
}

/** Customer's request to add selected suggested services to the reservation. */
data class RequestUpsellServicesRequest(
    val suggestionIds: List<String>
)

data class RequestUpsellServicesResponse(
    val smsSent: Boolean,
    val message: String,
    /** Suggestions after the request, so the card can re-render statuses without a refetch. */
    val suggestions: List<VisitCardUpsellSuggestion>
)
