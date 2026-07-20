package pl.detailing.crm.batchorder.domain

import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class BatchContractor(
    val id: BatchContractorId,
    val studioId: StudioId,
    val name: String,
    val taxId: String?,
    val address: String?,
    val contactPersonName: String?,
    val email: String?,
    val phone: String?,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)
