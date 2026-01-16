package pl.detailing.crm.visit.convert.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.convert.ConvertToVisitValidationContext

@Component
class VisitNotAlreadyExistsValidator {
    fun validate(context: ConvertToVisitValidationContext) {
        if (context.visitAlreadyExists) {
            throw ValidationException(
                "Visit already exists for appointment ${context.appointmentId}"
            )
        }
    }
}
