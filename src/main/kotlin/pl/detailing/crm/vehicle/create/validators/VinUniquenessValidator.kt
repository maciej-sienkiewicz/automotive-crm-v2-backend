package pl.detailing.crm.vehicle.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.create.CreateVehicleValidationContext

@Component
class VinUniquenessValidator {
    fun validate(context: CreateVehicleValidationContext) {
        if (context.vin != null && context.vinExists) {
            throw ValidationException("Vehicle with VIN '${context.vin}' already exists in this company")
        }

        if (context.licensePlateExists) {
            throw ValidationException("Vehicle with license plate '${context.licensePlate}' already exists in this company")
        }
    }
}
