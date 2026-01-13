package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class ServiceAvailabilityValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        val foundServiceIds = context.services.map { it.id }.toSet()
        val missingServiceIds = context.requestedServiceIds.filter { it !in foundServiceIds }

        if (missingServiceIds.isNotEmpty()) {
            throw ValidationException(
                "The following services are not available or not active in this studio: " +
                missingServiceIds.joinToString(", ") { it.toString() }
            )
        }

        if (context.services.isEmpty()) {
            throw ValidationException("At least one service must be selected for the appointment")
        }
    }
}
