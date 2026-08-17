package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

/** Batch projection: last post date per profile — feeds the storefront freshness check. */
interface ProfileLastPostAtProjection {
    val profileId: UUID
    val lastPostAt: Instant
}

@Repository
interface InstagramPostSnapshotRepository : JpaRepository<InstagramPostSnapshotEntity, UUID> {

    fun findByProfileIdOrderByTakenAtDesc(profileId: UUID): List<InstagramPostSnapshotEntity>

    fun findFirstByProfileIdOrderByTakenAtDesc(profileId: UUID): InstagramPostSnapshotEntity?

    @Query(
        """
        SELECT p.profileId AS profileId, MAX(p.takenAt) AS lastPostAt
        FROM InstagramPostSnapshotEntity p
        WHERE p.profileId IN :profileIds
        GROUP BY p.profileId
        """
    )
    fun findLastPostAtByProfileIds(@Param("profileIds") profileIds: Collection<UUID>): List<ProfileLastPostAtProjection>

    fun existsByPostPk(postPk: String): Boolean

    fun findByPostPkIn(postPks: List<String>): List<InstagramPostSnapshotEntity>

    fun existsByProfileId(profileId: UUID): Boolean

    fun deleteByProfileId(profileId: UUID)

    fun findByProfileIdAndTakenAtAfter(profileId: UUID, cutoff: Instant): List<InstagramPostSnapshotEntity>

    fun findByProfileIdInAndTakenAtAfter(profileIds: Collection<UUID>, cutoff: Instant): List<InstagramPostSnapshotEntity>

    /** Retencja: usuwa snapshoty postów starsze niż podany próg. Zwraca liczbę usuniętych. */
    fun deleteByTakenAtBefore(cutoff: Instant): Long
}
