package pl.detailing.crm.visit.domain

/**
 * The name a visit goes by in the activity feed.
 *
 * A visit number ("Wizyta #2026/0184") identifies a row in a table; it tells the reader
 * nothing about what was done or to whose car. The title the studio gave the visit does,
 * so it wins whenever it is set, with the vehicle as the next best thing a person
 * recognises and the number only as a last resort for visits that carry neither.
 *
 * Kept in one place because several modules name the same visit — the visit transitions,
 * the service operations and the financial documents issued against it — and a feed where
 * the same visit appears under three different names is a feed nobody can follow.
 */
object VisitAuditLabel {

    fun of(
        title: String?,
        brand: String?,
        model: String?,
        licensePlate: String?,
        visitNumber: String
    ): String =
        title?.takeIf { it.isNotBlank() }
            ?: vehicleLabel(brand, model, licensePlate)
            ?: "Wizyta #$visitNumber"

    /** "Audi A4 (WX 1234)", or null when the snapshot carries nothing usable. */
    fun vehicleLabel(brand: String?, model: String?, licensePlate: String?): String? =
        listOfNotNull(
            brand?.takeIf { it.isNotBlank() },
            model?.takeIf { it.isNotBlank() },
            licensePlate?.takeIf { it.isNotBlank() }?.let { "($it)" }
        ).joinToString(" ").takeIf { it.isNotBlank() }
}

/** How this visit reads in the activity feed. See [VisitAuditLabel]. */
val Visit.auditDisplayName: String
    get() = VisitAuditLabel.of(title, brandSnapshot, modelSnapshot, licensePlateSnapshot, visitNumber)
