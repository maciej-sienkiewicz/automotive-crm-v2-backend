package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.InstagramProfileStatus
import java.util.*

@Repository
interface StudioInstagramProfileRepository : JpaRepository<StudioInstagramProfileEntity, UUID> {

    fun findByStudioId(studioId: UUID): List<StudioInstagramProfileEntity>

    fun findByStudioIdAndProfileId(studioId: UUID, profileId: UUID): StudioInstagramProfileEntity?

    fun existsByStudioIdAndProfileId(studioId: UUID, profileId: UUID): Boolean

    fun findByStudioIdAndId(studioId: UUID, id: UUID): StudioInstagramProfileEntity?

    /**
     * Liczba aktywnych subskrypcji danego profilu (używane do decyzji o usunięciu global profilu)
     */
    fun countByProfileIdAndStatus(profileId: UUID, status: InstagramProfileStatus): Long

    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<StudioInstagramProfileEntity>
}
