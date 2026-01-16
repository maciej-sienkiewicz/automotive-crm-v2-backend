package pl.detailing.crm.visit.convert.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.convert.ConvertToVisitValidationContext

@Component
class AppointmentHasServicesValidator {
    fun validate(context: ConvertToVisitValidationContext) {
        val appointment = context.appointment ?: return

        if (appointment.lineItems.isEmpty()) {
            throw ValidationException(
                "Cannot convert appointment without services to visit"
            )
        }
    }
}
