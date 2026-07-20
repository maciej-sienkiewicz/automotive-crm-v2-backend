package pl.detailing.crm.batchorder.entry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId

@Service
class DeleteEntryHandler(
    private val entryRepository: BatchOrderEntryRepository
) {
    @Transactional
    suspend fun handle(command: DeleteEntryCommand) {
        val entity = entryRepository.findByIdAndStudioId(command.entryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Entry not found")

        entryRepository.delete(entity)
    }
}

data class DeleteEntryCommand(
    val studioId: StudioId,
    val entryId: BatchOrderEntryId
)
