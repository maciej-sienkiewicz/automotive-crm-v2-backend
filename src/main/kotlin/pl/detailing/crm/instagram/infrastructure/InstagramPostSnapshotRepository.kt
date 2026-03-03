package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface InstagramPostSnapshotRepository : JpaRepository<InstagramPostSnapshotEntity, UUID> {

    fun findByProfileIdOrderByTakenAtDesc(profileId: UUID): List<InstagramPostSnapshotEntity>

    fun existsByPostPk(postPk: String): Boolean

    fun deleteByProfileId(profileId: UUID)
}
