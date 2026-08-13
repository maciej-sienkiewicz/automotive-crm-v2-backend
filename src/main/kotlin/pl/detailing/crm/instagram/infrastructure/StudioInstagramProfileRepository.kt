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

    fun existsByStudioIdAndProfileIdAndStatus(studioId: UUID, profileId: UUID, status: InstagramProfileStatus): Boolean

    fun findByStudioIdAndStatus(studioId: UUID, status: InstagramProfileStatus): List<StudioInstagramProfileEntity>

    /** Własny profil studia ("Ty" w benchmarku) – maksymalnie jeden per studio. */
    fun findByStudioIdAndIsSelfTrue(studioId: UUID): StudioInstagramProfileEntity?

    /** Wszystkie studia z przynajmniej jednym aktywnym profilem – do tygodniowego przebiegu insightów. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT DISTINCT sip.studioId FROM StudioInstagramProfileEntity sip WHERE sip.status = :status"
    )
    fun findDistinctStudioIdsByStatus(status: InstagramProfileStatus): List<UUID>
}
