package pl.detailing.crm.vehicle.create

import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class CreateVehicleValidationContext(
    val studioId: StudioId,
    val ownerIds: List<CustomerId>,
    val licensePlate: String?,
    val yearOfProduction: Int?,
    /**
     * Every requested owner resolved WITH the studio filter, in [ownerIds] order; a null
     * entry means "not found in this studio". Validating only the first id used to let a
     * caller link another studio's customer as co-owner (and later read their name
     * through the vehicle list).
     */
    val owners: List<CustomerEntity?>,
    val licensePlateExists: Boolean
) {
    /** Kept for readers that only care about the primary owner. */
    val customerExists: CustomerEntity? get() = owners.firstOrNull()
}
