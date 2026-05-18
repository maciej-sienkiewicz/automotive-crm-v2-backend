package pl.detailing.crm.vehicle.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.create.CreateVehicleValidationContext

@Component
class OwnerAccessValidator {
    fun validate(context: CreateVehicleValidationContext) {
        val customer = context.customerExists
            ?: throw ValidationException("Klient o ID '${context.ownerIds[0]}' nie został znaleziony")

        if (customer.studioId != context.studioId.value) {
            throw ValidationException("Klient nie należy do tego studia")
        }

        if (!customer.isActive) {
            throw ValidationException("Nie można przypisać pojazdu do nieaktywnego klienta")
        }
    }
}
