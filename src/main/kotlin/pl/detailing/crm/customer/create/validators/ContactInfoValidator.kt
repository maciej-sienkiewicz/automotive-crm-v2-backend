package pl.detailing.crm.customer.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.create.CreateCustomerValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ContactInfoValidator {
    fun validate(context: CreateCustomerValidationContext) {
        if (context.email.isNullOrBlank() && context.phone.isNullOrBlank()) {
            throw ValidationException("Wymagany jest co najmniej numer telefonu lub adres email klienta.")
        }
    }
}
