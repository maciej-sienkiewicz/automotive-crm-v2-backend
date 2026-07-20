package pl.detailing.crm.batchorder.contractor

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.domain.BatchContractor
import pl.detailing.crm.batchorder.infrastructure.BatchContractorEntity
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

@Service
class CreateContractorHandler(
    private val contractorRepository: BatchContractorRepository
) {
    @Transactional
    suspend fun handle(command: CreateContractorCommand): ContractorListItem {
        if (command.name.isBlank()) throw ValidationException("Contractor name cannot be empty")

        val contractor = BatchContractor(
            id = BatchContractorId.random(),
            studioId = command.studioId,
            name = command.name.trim(),
            taxId = command.taxId?.trim()?.takeIf { it.isNotBlank() },
            address = command.address?.trim()?.takeIf { it.isNotBlank() },
            contactPersonName = command.contactPersonName?.trim()?.takeIf { it.isNotBlank() },
            email = command.email?.trim()?.takeIf { it.isNotBlank() },
            phone = command.phone?.trim()?.takeIf { it.isNotBlank() },
            notes = command.notes?.trim()?.takeIf { it.isNotBlank() },
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val saved = contractorRepository.save(BatchContractorEntity.fromDomain(contractor))
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
            entryCount = 0,
            createdAt = saved.createdAt.toString(),
            updatedAt = saved.updatedAt.toString()
        )
    }
}

data class CreateContractorCommand(
    val studioId: StudioId,
    val name: String,
    val taxId: String?,
    val address: String?,
    val contactPersonName: String?,
    val email: String?,
    val phone: String?,
    val notes: String?
)
