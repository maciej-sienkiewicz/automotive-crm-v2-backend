package pl.detailing.crm.vehicle.create

import org.springframework.stereotype.Component
import pl.detailing.crm.vehicle.create.validators.OwnerAccessValidator
import pl.detailing.crm.vehicle.create.validators.ProductionYearValidator

@Component
class CreateVehicleValidatorComposite(
    private val contextBuilder: CreateVehicleValidationContextBuilder,
    private val ownerAccessValidator: OwnerAccessValidator,
    private val productionYearValidator: ProductionYearValidator
) {
    suspend fun validate(command: CreateVehicleCommand) {
        val context = contextBuilder.build(command)

        ownerAccessValidator.validate(context)
        productionYearValidator.validate(context)
    }
}
