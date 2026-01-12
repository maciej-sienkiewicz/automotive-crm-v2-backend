package pl.detailing.crm.studio.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface StudioRepository : JpaRepository<StudioEntity, UUID> {

    @Query("SELECT s FROM StudioEntity s WHERE s.id = :id")
    fun findByStudioId(@Param("id") id: UUID): StudioEntity?

    @Query("SELECT s FROM StudioEntity s WHERE s.name = :name")
    fun findByName(@Param("name") name: String): StudioEntity?

    @Query("""
        SELECT s FROM StudioEntity s 
        WHERE s.subscriptionStatus = 'TRIALING' 
        AND s.trialEndsAt < :now
    """)
    fun findExpiredTrials(@Param("now") now: Instant): List<StudioEntity>
}