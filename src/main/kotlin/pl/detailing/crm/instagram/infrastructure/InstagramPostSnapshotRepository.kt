package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

@Repository
interface InstagramPostSnapshotRepository : JpaRepository<InstagramPostSnapshotEntity, UUID> {

    fun findByProfileIdOrderByTakenAtDesc(profileId: UUID): List<InstagramPostSnapshotEntity>

    fun existsByPostPk(postPk: String): Boolean

    fun findByPostPkIn(postPks: List<String>): List<InstagramPostSnapshotEntity>

    fun existsByProfileId(profileId: UUID): Boolean

    fun deleteByProfileId(profileId: UUID)

    fun findByProfileIdAndTakenAtAfter(profileId: UUID, cutoff: Instant): List<InstagramPostSnapshotEntity>

    fun findByProfileIdInAndTakenAtAfter(profileIds: Collection<UUID>, cutoff: Instant): List<InstagramPostSnapshotEntity>

    /** Retencja: usuwa snapshoty postów starsze niż podany próg. Zwraca liczbę usuniętych. */
    fun deleteByTakenAtBefore(cutoff: Instant): Long
}
