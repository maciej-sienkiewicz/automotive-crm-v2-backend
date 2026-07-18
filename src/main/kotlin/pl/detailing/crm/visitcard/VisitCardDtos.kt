package pl.detailing.crm.visitcard

import java.time.Instant

/**
 * Customer-facing Visit Card payload served on the public (tokenized) endpoint.
 *
 * The card discloses information progressively:
 *  - base sections are always present,
 *  - [inProgress] appears once the visit has started (documents signed, vehicle admitted),
 *  - [completion] appears once the vehicle is ready for pickup / handed over.
 *
 * No internal identifiers (visit/customer/studio UUIDs) are exposed — the token is the
 * only reference the customer ever sees.
 */
data class VisitCardResponse(
    val visitNumber: String,
    val title: String?,
    /** Visit status name, or "RESERVATION" for a not-yet-checked-in reservation card. */
    val status: String,
    val reservation: VisitCardReservation,
    /** Null for reservations that don't have a vehicle assigned yet. */
    val vehicle: VisitCardVehicle?,
    val customer: VisitCardCustomer,
    val company: VisitCardCompany,
    val services: List<VisitCardServiceLine>,
    val totals: VisitCardTotals,
    val inProgress: VisitCardInProgress?,
    val completion: VisitCardCompletion?,
    /** Suggested additional services (upselling) — empty when none were assigned. */
    val upsellSuggestions: List<pl.detailing.crm.visitcard.upsell.VisitCardUpsellSuggestion>
)

data class VisitCardReservation(
    val scheduledDate: Instant,
    val estimatedCompletionDate: Instant?
)

data class VisitCardVehicle(
    val brand: String,
    val model: String,
    val licensePlate: String?,
    val yearOfProduction: Int?,
    val color: String?
)

data class VisitCardCustomer(
    val firstName: String?,
    val lastName: String?
)

data class VisitCardCompany(
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val phone: String?,
    val email: String?,
    val website: String?,
    val logoUrl: String?
)

data class VisitCardServiceLine(
    val name: String,
    val note: String?,
    val priceGross: Long,   // grosz
    val priceNet: Long      // grosz
)

data class VisitCardTotals(
    val totalNet: Long,
    val totalGross: Long,
    /** Total discount applied across all service lines; 0 when no discounts were given. */
    val totalDiscountGross: Long,
    val currency: String
)

/** Section visible once the visit has started (status beyond DRAFT). */
data class VisitCardInProgress(
    val admissionDate: Instant,
    val signedConsents: List<VisitCardSignedDocument>,
    val photos: List<VisitCardPhoto>,
    val damageMapUrl: String?
)

data class VisitCardSignedDocument(
    val name: String,
    val signedAt: Instant?,
    val downloadUrl: String?
)

data class VisitCardPhoto(
    val url: String,
    val description: String?,
    val uploadedAt: Instant
)

/** Section visible once the work is finished (READY_FOR_PICKUP and beyond). */
data class VisitCardCompletion(
    val readyForPickupDate: Instant?,
    val pickupDate: Instant?,
    val documents: List<VisitCardDocument>,
    /** PAID / PENDING / OVERDUE, or null when no payment record exists yet. */
    val paymentStatus: String?
)

data class VisitCardDocument(
    val name: String,
    val fileName: String,
    val downloadUrl: String?,
    val uploadedAt: Instant
)
