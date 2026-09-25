package pl.detailing.crm.batchorder.entry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.contractor.EntryItem
import pl.detailing.crm.batchorder.contractor.toEntryItem
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoRepository
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/**
 * Odblokowanie rozliczonego wpisu do korekty — jedyna droga do zmiany wpisu, który
 * już był na zestawieniu. Wpis wraca do otwartych jako korekta i trafi do następnego
 * rozliczenia; dokument poprzedniego się nie zmienia, bo ma własny snapshot pozycji.
 *
 * Idempotentne: otwarty wpis wraca bez zmian (i bez flagi korekty).
 */
@Service
class ReopenEntryHandler(
    private val entryRepository: BatchOrderEntryRepository,
    private val photoRepository: BatchOrderPhotoRepository
) {
    @Transactional
    suspend fun handle(command: ReopenEntryCommand): EntryItem {
        val entity = entryRepository.findByIdAndStudioId(command.entryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Entry not found")

        val saved = if (entity.reopenForCorrection(Instant.now())) entryRepository.save(entity) else entity
        return saved.toEntryItem(
            photoCount = photoRepository.countByEntryIdAndStudioId(saved.id, command.studioId.value).toInt()
        )
    }
}

data class ReopenEntryCommand(
    val studioId: StudioId,
    val entryId: BatchOrderEntryId
)
