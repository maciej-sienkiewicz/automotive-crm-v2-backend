package pl.detailing.crm.visit.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class UpdateServiceStatusHandler(
    private val validatorComposite: UpdateServiceStatusValidatorComposite,
    private val visitRepository: VisitRepository
) {
    @Transactional
    suspend fun handle(command: UpdateServiceStatusCommand): Unit =
        withContext(Dispatchers.IO) {
            // Step 1: Validate
            validatorComposite.validate(command)

            // Step 2: Load visit entity
            val visitEntity = visitRepository.findByIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            )!!

            // Step 3: Find and update service item status
            val serviceItemEntity = visitEntity.serviceItems.find {
                it.id == command.serviceItemId.value
            }!!

            serviceItemEntity.status = command.newStatus

            // Step 4: Update visit metadata
            visitEntity.updatedBy = command.userId.value
            visitEntity.updatedAt = Instant.now()

            // Step 5: Persist changes
            visitRepository.save(visitEntity)
        }
}
