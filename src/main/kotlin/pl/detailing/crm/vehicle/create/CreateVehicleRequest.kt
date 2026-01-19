package pl.detailing.crm.vehicle.create

data class CreateVehicleRequest(
    val ownerIds: List<String>,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Int
)
