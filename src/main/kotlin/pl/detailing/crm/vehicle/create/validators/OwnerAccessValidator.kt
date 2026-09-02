package pl.detailing.crm.vehicle.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.create.CreateVehicleValidationContext

@Component
class OwnerAccessValidator {
    fun validate(context: CreateVehicleValidationContext) {
        if (context.ownerIds.isEmpty()) {
            throw ValidationException("Pojazd musi mieć co najmniej jednego właściciela")
        }
        if (context.ownerIds.size != context.ownerIds.toSet().size) {
            throw ValidationException("Lista właścicieli zawiera powtórzenia")
        }

        context.ownerIds.forEachIndexed { index, ownerId ->
            // Not found and not-in-this-studio are one and the same answer: the lookup was
            // already studio-scoped, and the message must not reveal that the id exists.
            val customer = context.owners.getOrNull(index)
                ?: throw EntityNotFoundException("Klient o ID '$ownerId' nie został znaleziony")

            if (customer.studioId != context.studioId.value) {
                throw EntityNotFoundException("Klient o ID '$ownerId' nie został znaleziony")
            }

            if (!customer.isActive) {
                throw ValidationException("Nie można przypisać pojazdu do nieaktywnego klienta")
            }
        }
    }
}
