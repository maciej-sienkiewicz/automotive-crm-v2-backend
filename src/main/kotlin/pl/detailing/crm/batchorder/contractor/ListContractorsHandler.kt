package pl.detailing.crm.batchorder.contractor

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.shared.StudioId

@Service
class ListContractorsHandler(
    private val contractorRepository: BatchContractorRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: ListContractorsCommand): ListContractorsResult {
        val entities = contractorRepository.findActiveByStudioId(command.studioId.value)
        val items = entities.map { entity ->
            val entryCount = contractorRepository.countEntriesByContractorId(entity.id, command.studioId.value)
            ContractorListItem(
                id = entity.id.toString(),
                name = entity.name,
                taxId = entity.taxId,
                address = entity.address,
                contactPersonName = entity.contactPersonName,
                email = entity.email,
                phone = entity.phone,
                notes = entity.notes,
                isActive = entity.isActive,
                entryCount = entryCount,
                createdAt = entity.createdAt.toString(),
                updatedAt = entity.updatedAt.toString()
            )
        }
        return ListContractorsResult(contractors = items)
    }
}

data class ListContractorsCommand(val studioId: StudioId)

data class ListContractorsResult(val contractors: List<ContractorListItem>)

data class ContractorListItem(
    val id: String,
    val name: String,
    val taxId: String?,
    val address: String?,
    val contactPersonName: String?,
    val email: String?,
    val phone: String?,
    val notes: String?,
    val isActive: Boolean,
    val entryCount: Long,
    val createdAt: String,
    val updatedAt: String
)
