package pl.detailing.crm.customer.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.create.CreateCustomerValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ContactUniquenessValidator {
    fun validate(context: CreateCustomerValidationContext) {
        if (context.emailExists) {
            throw ValidationException("Klient z adresem email '${context.email}' już istnieje w tym studiu")
        }

        if (context.phoneExists) {
            throw ValidationException("Klient z numerem telefonu '${context.phone}' już istnieje w tym studiu")
        }
    }
}
