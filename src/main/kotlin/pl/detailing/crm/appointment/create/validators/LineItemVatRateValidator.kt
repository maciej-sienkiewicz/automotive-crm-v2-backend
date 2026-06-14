package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate

@Component
class LineItemVatRateValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        val allowedRates = VatRate.entries.map { it.rate }

        val invalidItems = context.requestedServiceLineItems.filter { it.vatRate !in allowedRates }

        if (invalidItems.isNotEmpty()) {
            val invalidRates = invalidItems.map { it.vatRate }.distinct().joinToString(", ")
            throw ValidationException(
                "Nieprawidłowa stawka VAT: $invalidRates. Dozwolone stawki: ${allowedRates.joinToString(", ")}"
            )
        }
    }
}
