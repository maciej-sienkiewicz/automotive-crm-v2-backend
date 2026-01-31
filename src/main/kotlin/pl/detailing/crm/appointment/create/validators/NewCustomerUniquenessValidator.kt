package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.appointment.create.CustomerIdentity
import pl.detailing.crm.shared.ValidationException

@Component
class NewCustomerUniquenessValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        when (val identity = context.customerIdentity) {
            is CustomerIdentity.New
            -> {
                if (identity.email != null && context.customerEmailExists) {
                    throw ValidationException(
                        "Customer with email '${identity.email}' already exists in this studio"
                    )
                }

                if (identity.phone != null && context.customerPhoneExists) {
                    throw ValidationException(
                        "Customer with phone '${identity.phone}' already exists in this studio"
                    )
                }
            }
            is CustomerIdentity.Existing -> {
                // No validation needed for existing customer
            }
            is CustomerIdentity.Update
                -> {
                if (identity.email != null && context.customerEmailExists) {
                    throw ValidationException(
                        "Customer with email '${identity.email}' already exists in this studio"
                    )
                }
            }
        }
    }
}
