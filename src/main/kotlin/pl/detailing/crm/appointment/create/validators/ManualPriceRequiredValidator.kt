package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.ValidationException

/**
 * Validates that services requiring manual price input have explicit price set
 */
@Component
class ManualPriceRequiredValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        val servicesById = context.services.associateBy { it.id }

        val lineItemsRequiringManualPrice = context.requestedServiceLineItems
            .filter { lineItem ->
                val service = servicesById[lineItem.serviceId]
                service?.requireManualPrice == true
            }

        val lineItemsWithoutManualPrice = lineItemsRequiringManualPrice
            .filter { lineItem ->
                lineItem.adjustmentType != AdjustmentType.SET_NET &&
                lineItem.adjustmentType != AdjustmentType.SET_GROSS
            }

        if (lineItemsWithoutManualPrice.isNotEmpty()) {
            val serviceNames = lineItemsWithoutManualPrice
                .mapNotNull { lineItem -> servicesById[lineItem.serviceId]?.name }
                .joinToString(", ")

            throw ValidationException(
                "The following services require manual price input: $serviceNames. " +
                "Please provide an explicit price for each service."
            )
        }
    }
}
