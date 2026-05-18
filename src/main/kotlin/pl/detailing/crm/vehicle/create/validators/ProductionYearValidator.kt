package pl.detailing.crm.vehicle.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.create.CreateVehicleValidationContext
import java.time.Year

@Component
class ProductionYearValidator {
    fun validate(context: CreateVehicleValidationContext) {
        // If year of production is null, no validation needed
        val year = context.yearOfProduction ?: return

        val currentYear = Year.now().value
        val minYear = 1900

        if (year > currentYear) {
            throw ValidationException("Rok produkcji nie może być w przyszłości (aktualny rok: $currentYear)")
        }

        if (year < minYear) {
            throw ValidationException("Rok produkcji musi być większy niż $minYear")
        }
    }
}
