package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.appointment.create.CustomerIdentity
import pl.detailing.crm.shared.ValidationException

@Component
class CustomerContactInfoValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        when (val identity = context.customerIdentity) {
            is CustomerIdentity.New -> {
                if (identity.phone.isNullOrBlank() && identity.email.isNullOrBlank()) {
                    throw ValidationException("Wymagany jest co najmniej numer telefonu lub adres email klienta.")
                }
            }
            is CustomerIdentity.Update -> {
                if (identity.phone.isNullOrBlank() && identity.email.isNullOrBlank()) {
                    throw ValidationException("Wymagany jest co najmniej numer telefonu lub adres email klienta.")
                }
            }
            is CustomerIdentity.Existing -> {
                // Istniejący klient powinien już mieć dane lub być zwalidowany wcześniej
            }
        }
    }
}
