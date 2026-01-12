package pl.detailing.crm.service.update.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.service.update.UpdateServiceValidationContext
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate

@Component
class UpdateVatRateValidator {
    fun validate(context: UpdateServiceValidationContext) {
        val allowedRates = VatRate.entries.map { it.rate }

        if (context.vatRate.rate !in allowedRates) {
            throw ValidationException(
                "Invalid VAT rate. Allowed rates: ${allowedRates.joinToString(", ")}"
            )
        }
    }
}