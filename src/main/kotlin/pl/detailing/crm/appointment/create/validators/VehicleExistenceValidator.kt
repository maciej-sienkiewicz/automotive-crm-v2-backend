package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.appointment.create.VehicleIdentity
import pl.detailing.crm.shared.EntityNotFoundException

@Component
class VehicleExistenceValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        when (val identity = context.vehicleIdentity) {
            is VehicleIdentity.Existing -> {
                if (context.existingVehicle == null) {
                    throw EntityNotFoundException(
                        "Pojazd o ID '${identity.vehicleId}' nie został znaleziony w tym studiu"
                    )
                }
            }
            is VehicleIdentity.Update -> {
                if (context.existingVehicle == null) {
                    throw EntityNotFoundException(
                        "Pojazd o ID '${identity.vehicleId}' nie został znaleziony w tym studiu"
                    )
                }
            }
            is VehicleIdentity.New, VehicleIdentity.None -> {
                // No validation needed for new or no vehicle
            }
        }
    }
}
