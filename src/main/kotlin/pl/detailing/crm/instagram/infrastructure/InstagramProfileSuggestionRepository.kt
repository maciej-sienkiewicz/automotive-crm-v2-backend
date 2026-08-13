package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

@Repository
interface InstagramProfileSuggestionRepository : JpaRepository<InstagramProfileSuggestionEntity, UUID> {

    fun findByProfileIdIn(profileIds: Collection<UUID>): List<InstagramProfileSuggestionEntity>

    fun deleteByProfileId(profileId: UUID)

    /** Najświeższy fetch dla profilu – do decyzji o odświeżeniu (TTL 30 dni). */
    fun findFirstByProfileIdOrderByFetchedAtDesc(profileId: UUID): InstagramProfileSuggestionEntity?

    fun deleteByFetchedAtBefore(cutoff: Instant): Long
}
