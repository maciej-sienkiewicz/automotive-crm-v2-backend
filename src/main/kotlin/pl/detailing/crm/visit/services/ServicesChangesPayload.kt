package pl.detailing.crm.visit.services

import pl.detailing.crm.appointment.domain.AdjustmentType

data class ServicesChangesPayload(
    val notifyCustomer: Boolean,
    val requireConfirmation: Boolean = true,
    val added: List<AddedService>,
    val updated: List<UpdatedService>,
    val deleted: List<DeletedService>
)

data class AddedService(
    val serviceId: String?,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: ServiceAdjustment?,
    val note: String?
)

data class ServiceAdjustment(
    val type: AdjustmentType,
    val value: Double  // Double to support decimal percentages like -49.19
)

data class UpdatedService(
    val serviceLineItemId: String,
    val basePriceNet: Long,
    val vatRate: Int? = null,
    val adjustment: ServiceAdjustment? = null
)

data class DeletedService(
    val serviceLineItemId: String
)
