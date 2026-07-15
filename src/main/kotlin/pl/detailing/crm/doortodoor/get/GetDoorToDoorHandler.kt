package pl.detailing.crm.doortodoor.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId

data class GetDoorToDoorCommand(
    val studioId: StudioId,
    val visitId: VisitId
)

@Service
class GetDoorToDoorHandler(
    private val doorToDoorRepository: DoorToDoorRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: GetDoorToDoorCommand): DoorToDoor? =
        withContext(Dispatchers.IO) {
            doorToDoorRepository
                .findByVisitIdAndStudioId(command.visitId.value, command.studioId.value)
                ?.toDomain()
        }
}
