package pl.detailing.crm.batchorder.contractor

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

@Service
class UpdateContractorHandler(
    private val contractorRepository: BatchContractorRepository
) {
    @Transactional
    suspend fun handle(command: UpdateContractorCommand): ContractorListItem {
        if (command.name.isBlank()) throw ValidationException("Contractor name cannot be empty")

        val entity = contractorRepository.findByIdAndStudioId(command.contractorId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Contractor not found")

        entity.name = command.name.trim()
        entity.taxId = command.taxId?.trim()?.takeIf { it.isNotBlank() }
        entity.address = command.address?.trim()?.takeIf { it.isNotBlank() }
        entity.contactPersonName = command.contactPersonName?.trim()?.takeIf { it.isNotBlank() }
        entity.email = command.email?.trim()?.takeIf { it.isNotBlank() }
        entity.phone = command.phone?.trim()?.takeIf { it.isNotBlank() }
        entity.notes = command.notes?.trim()?.takeIf { it.isNotBlank() }
        entity.updatedAt = Instant.now()

        val saved = contractorRepository.save(entity)
        val entryCount = contractorRepository.countEntriesByContractorId(saved.id, command.studioId.value)

        return ContractorListItem(
            id = saved.id.toString(),
            name = saved.name,
            taxId = saved.taxId,
            address = saved.address,
            contactPersonName = saved.contactPersonName,
            email = saved.email,
            phone = saved.phone,
            notes = saved.notes,
            isActive = saved.isActive,
            entryCount = entryCount,
            createdAt = saved.createdAt.toString(),
            updatedAt = saved.updatedAt.toString()
        )
    }
}

data class UpdateContractorCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId,
    val name: String,
    val taxId: String?,
    val address: String?,
    val contactPersonName: String?,
    val email: String?,
    val phone: String?,
    val notes: String?
)
