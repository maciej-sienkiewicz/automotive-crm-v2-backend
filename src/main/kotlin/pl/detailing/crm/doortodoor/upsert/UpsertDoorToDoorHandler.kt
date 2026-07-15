package pl.detailing.crm.doortodoor.upsert

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.doortodoor.domain.DoorToDoorAddress
import pl.detailing.crm.doortodoor.domain.DoorToDoorStatus
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorEntity
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.shared.DoorToDoorId
import java.time.Instant

@Service
class UpsertDoorToDoorHandler(
    private val doorToDoorRepository: DoorToDoorRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: UpsertDoorToDoorCommand): DoorToDoor =
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val existing = doorToDoorRepository.findByVisitIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            )

            val entity = if (existing != null) {
                existing.pickupCity = command.pickupCity
                existing.pickupStreet = command.pickupStreet
                existing.deliveryCity = command.deliveryCity
                existing.deliveryStreet = command.deliveryStreet
                existing.notes = command.notes
                existing.updatedBy = command.userId.value
                existing.updatedAt = now
                existing
            } else {
                DoorToDoorEntity.fromDomain(
                    DoorToDoor(
                        id = DoorToDoorId.random(),
                        studioId = command.studioId,
                        visitId = command.visitId,
                        pickupAddress = DoorToDoorAddress(command.pickupCity, command.pickupStreet),
                        deliveryAddress = DoorToDoorAddress(command.deliveryCity, command.deliveryStreet),
                        notes = command.notes,
                        status = DoorToDoorStatus.SCHEDULED,
                        createdBy = command.userId,
                        updatedBy = command.userId,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }

            val saved = doorToDoorRepository.save(entity)

            auditService.log(
                LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName,
                    module = AuditModule.DOOR_TO_DOOR,
                    entityId = saved.id.toString(),
                    entityDisplayName = "Door to Door – wizyta ${command.visitId}",
                    action = if (existing != null) AuditAction.UPDATE else AuditAction.CREATE,
                    changes = emptyList(),
                    metadata = mapOf("visitId" to command.visitId.toString())
                )
            )

            saved.toDomain()
        }
}
