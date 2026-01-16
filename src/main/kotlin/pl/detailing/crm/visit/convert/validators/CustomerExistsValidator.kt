package pl.detailing.crm.visit.convert.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.convert.ConvertToVisitValidationContext

@Component
class CustomerExistsValidator {
    fun validate(context: ConvertToVisitValidationContext) {
        if (context.customer == null) {
            throw ValidationException(
                "Customer with ID ${context.appointment?.customerId} not found"
            )
        }
    }
}
