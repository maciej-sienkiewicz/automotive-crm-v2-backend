package pl.detailing.crm.service.archive

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*

data class ArchiveServiceCommand(
    val studioId: StudioId,
    val serviceId: ServiceId,
    val userId: UserId
)

@Service
class ArchiveServiceHandler(
    private val serviceRepository: ServiceRepository
) {
    @Transactional
    fun handle(command: ArchiveServiceCommand) {
        val entity = serviceRepository.findByIdAndStudioId(
            command.serviceId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Usługa nie została znaleziona")

        if (!entity.isActive) {
            throw ValidationException("Usługa jest już zarchiwizowana")
        }

        val archived = entity.toDomain().archive()
        entity.isActive = archived.isActive
        entity.updatedBy = command.userId.value
        entity.updatedAt = archived.updatedAt

        serviceRepository.save(entity)
    }
}
