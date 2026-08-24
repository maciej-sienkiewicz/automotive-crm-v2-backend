package pl.detailing.crm.push.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PushDeviceRepository : JpaRepository<PushDeviceEntity, UUID> {

    fun findByEndpointHash(endpointHash: String): PushDeviceEntity?

    fun findByStudioIdAndUserIdOrderByCreatedAtDesc(studioId: UUID, userId: UUID): List<PushDeviceEntity>

    fun findByStudioIdAndUserIdAndRevokedAtIsNull(studioId: UUID, userId: UUID): List<PushDeviceEntity>

    fun findByIdAndStudioIdAndUserId(id: UUID, studioId: UUID, userId: UUID): PushDeviceEntity?
}
