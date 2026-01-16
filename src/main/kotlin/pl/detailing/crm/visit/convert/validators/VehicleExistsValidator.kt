package pl.detailing.crm.visit.convert.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.convert.ConvertToVisitValidationContext

@Component
class VehicleExistsValidator {
    fun validate(context: ConvertToVisitValidationContext) {
        if (context.appointment?.vehicleId == null) {
            throw ValidationException("Appointment must have a vehicle assigned before conversion")
        }

        if (context.vehicle == null) {
            throw ValidationException(
                "Vehicle with ID ${context.appointment.vehicleId} not found"
            )
        }
    }
}
