package pl.detailing.crm.doortodoor.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DoorToDoorRepository : JpaRepository<DoorToDoorEntity, UUID> {
    fun findByVisitIdAndStudioId(visitId: UUID, studioId: UUID): DoorToDoorEntity?
    fun findByVisitId(visitId: UUID): DoorToDoorEntity?
}
