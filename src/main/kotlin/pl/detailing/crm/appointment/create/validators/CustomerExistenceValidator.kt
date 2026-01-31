package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.appointment.create.CustomerIdentity
import pl.detailing.crm.shared.EntityNotFoundException

@Component
class CustomerExistenceValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        when (val identity = context.customerIdentity) {
            is CustomerIdentity.Existing -> {
                if (context.existingCustomer == null) {
                    throw EntityNotFoundException(
                        "Customer with ID '${identity.customerId}' not found in this studio"
                    )
                }
            }
            is CustomerIdentity.Update -> {
                if (context.existingCustomer == null) {
                    throw EntityNotFoundException(
                        "Customer with ID '${identity.customerId}' not found in this studio"
                    )
                }
            }
            is CustomerIdentity.New -> {
                // Validation for new customer creation will be handled by separate validators
            }
        }
    }
}
