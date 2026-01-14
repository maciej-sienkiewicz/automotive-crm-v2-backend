package pl.detailing.crm.customer.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.create.CreateCustomerValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ContactUniquenessValidator {
    fun validate(context: CreateCustomerValidationContext) {
        if (context.emailExists) {
            throw ValidationException("Customer with email '${context.email}' already exists in this studio")
        }

        if (context.phoneExists) {
            throw ValidationException("Customer with phone '${context.phone}' already exists in this studio")
        }
    }
}
