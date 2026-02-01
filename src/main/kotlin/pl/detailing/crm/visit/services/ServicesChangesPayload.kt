package pl.detailing.crm.visit.services

import pl.detailing.crm.appointment.domain.AdjustmentType

data class ServicesChangesPayload(
    val notifyCustomer: Boolean,
    val added: List<AddedService>,
    val updated: List<UpdatedService>,
    val deleted: List<DeletedService>
)

data class AddedService(
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Double,
    val vatRate: Int,
    val adjustment: ServiceAdjustment?,
    val note: String?
)

data class ServiceAdjustment(
    val type: AdjustmentType,
    val value: Long
)

data class UpdatedService(
    val serviceLineItemId: String,
    val basePriceNet: Double
)

data class DeletedService(
    val serviceLineItemId: String
)
