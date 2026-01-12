package pl.detailing.crm.service.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.service.create.CreateServiceValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class PriceValidator {
    fun validate(context: CreateServiceValidationContext) {
        if (context.basePriceNet.amountInCents <= 0) {
            throw ValidationException("Base price must be greater than zero")
        }

        if (context.basePriceNet.amountInCents > 100_000_000) {
            throw ValidationException("Base price cannot exceed 1,000,000.00")
        }
    }
}
