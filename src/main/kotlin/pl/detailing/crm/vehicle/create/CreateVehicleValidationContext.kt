package pl.detailing.crm.vehicle.create

import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class CreateVehicleValidationContext(
    val studioId: StudioId,
    val customerId: CustomerId,
    val licensePlate: String,
    val vin: String?,
    val yearOfProduction: Int,
    val customerExists: CustomerEntity?,
    val vinExists: Boolean,
    val licensePlateExists: Boolean
)
