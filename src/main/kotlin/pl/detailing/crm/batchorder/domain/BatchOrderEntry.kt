package pl.detailing.crm.batchorder.domain

import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate

data class BatchOrderServiceItem(
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int
)

data class BatchOrderEntry(
    val id: BatchOrderEntryId,
    val studioId: StudioId,
    val contractorId: BatchContractorId,
    val serviceDate: LocalDate,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val services: List<BatchOrderServiceItem>,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
