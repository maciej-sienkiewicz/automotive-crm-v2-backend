package pl.detailing.crm.vehicle.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.create.CreateVehicleValidationContext
import java.time.Year

@Component
class ProductionYearValidator {
    fun validate(context: CreateVehicleValidationContext) {
        val currentYear = Year.now().value
        val minYear = 1900

        if (context.yearOfProduction > currentYear) {
            throw ValidationException("Production year cannot be in the future (current year: $currentYear)")
        }

        if (context.yearOfProduction < minYear) {
            throw ValidationException("Production year must be greater than $minYear")
        }
    }
}
