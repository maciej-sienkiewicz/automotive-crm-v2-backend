package pl.detailing.crm.vehicle.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.create.CreateVehicleValidationContext

@Component
class OwnerAccessValidator {
    fun validate(context: CreateVehicleValidationContext) {
        val customer = context.customerExists
            ?: throw ValidationException("Customer with ID '${context.customerId}' not found")

        if (customer.studioId != context.studioId.value) {
            throw ValidationException("Customer does not belong to the same company")
        }

        if (!customer.isActive) {
            throw ValidationException("Cannot assign vehicle to inactive customer")
        }
    }
}
