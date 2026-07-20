package pl.detailing.crm.batchorder.contractor

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.Instant

@Service
class DeleteContractorHandler(
    private val contractorRepository: BatchContractorRepository
) {
    @Transactional
    suspend fun handle(command: DeleteContractorCommand) {
        val entity = contractorRepository.findByIdAndStudioId(command.contractorId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Contractor not found")

        entity.isActive = false
        entity.updatedAt = Instant.now()
        contractorRepository.save(entity)
    }
}

data class DeleteContractorCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId
)
