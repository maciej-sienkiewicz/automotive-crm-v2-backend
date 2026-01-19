package pl.detailing.crm.vehicle.create

import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class CreateVehicleValidationContext(
    val studioId: StudioId,
    val ownerIds: List<CustomerId>,
    val licensePlate: String?,
    val yearOfProduction: Int?,
    val customerExists: CustomerEntity?,
    val licensePlateExists: Boolean
)
