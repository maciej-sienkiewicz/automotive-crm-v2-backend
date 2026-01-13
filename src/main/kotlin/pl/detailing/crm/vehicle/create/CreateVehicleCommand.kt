package pl.detailing.crm.vehicle.create

import pl.detailing.crm.shared.*

data class CreateVehicleCommand(
    val studioId: StudioId,
    val userId: UserId,
    val customerId: CustomerId,
    val licensePlate: String,
    val brand: String,
    val model: String,
    val vin: String?,
    val yearOfProduction: Int,
    val color: String?,
    val paintType: String?,
    val engineType: EngineType,
    val currentMileage: Int
)
