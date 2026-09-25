package pl.detailing.crm.batchorder.contractor

import pl.detailing.crm.shared.pii.Pii
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorEntity
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.infrastructure.entryCountsByContractorId
import pl.detailing.crm.shared.StudioId

@Service
class ListContractorsHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: ListContractorsCommand): ListContractorsResult {
        val entities = contractorRepository.findActiveByStudioId(command.studioId.value)
        // Jedno zapytanie grupujące zamiast COUNT-a na każdego kontrahenta.
        val entryCounts = entryRepository.entryCountsByContractorId(command.studioId.value)
        val items = entities.map { it.toListItem(entryCount = entryCounts[it.id] ?: 0L) }
        return ListContractorsResult(contractors = items)
    }
}

fun BatchContractorEntity.toListItem(entryCount: Long) = ContractorListItem(
    id = id.toString(),
    name = name,
    taxId = taxId,
    address = address,
    contactPersonName = contactPersonName,
    email = email,
    phone = phone,
    notes = notes,
    isActive = isActive,
    entryCount = entryCount,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

data class ListContractorsCommand(val studioId: StudioId)

data class ListContractorsResult(val contractors: List<ContractorListItem>)

data class ContractorListItem(
    val id: String,
    val name: String,
    val taxId: String?,
    val address: String?,
    val contactPersonName: String?,
    @Pii val email: String?,
    @Pii val phone: String?,
    val notes: String?,
    val isActive: Boolean,
    val entryCount: Long,
    val createdAt: String,
    val updatedAt: String
)
