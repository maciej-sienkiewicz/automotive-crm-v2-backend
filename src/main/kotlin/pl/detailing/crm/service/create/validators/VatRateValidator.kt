package pl.detailing.crm.service.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.service.create.CreateServiceValidationContext
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate

@Component
class VatRateValidator {
    fun validate(context: CreateServiceValidationContext) {
        val allowedRates = VatRate.entries.map { it.rate }

        if (context.vatRate.rate !in allowedRates) {
            throw ValidationException(
                "Invalid VAT rate. Allowed rates: ${allowedRates.joinToString(", ")}"
            )
        }
    }
}