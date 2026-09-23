package pl.detailing.crm.studio.infrastructure

import pl.detailing.crm.studio.domain.StudioKind
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

    /** Sam rodzaj studia - bez ładowania encji; null, gdy studia nie ma. */
    @Query("SELECT s.kind FROM StudioEntity s WHERE s.id = :id")
    fun findKindById(@Param("id") id: UUID): StudioKind?

    @Query("SELECT s.id FROM StudioEntity s WHERE s.kind = :kind AND s.createdAt < :createdBefore")
    fun findIdsByKindCreatedBefore(
        @Param("kind") kind: StudioKind,
        @Param("createdBefore") createdBefore: Instant
    ): List<UUID>

    @Query("SELECT s FROM StudioEntity s WHERE s.name = :name")
    fun findByName(@Param("name") name: String): StudioEntity?

    @Query("SELECT s FROM StudioEntity s WHERE s.emailAlias = :emailAlias")
    fun findByEmailAlias(@Param("emailAlias") emailAlias: String): StudioEntity?

    @Query("""
        SELECT s FROM StudioEntity s
        WHERE s.subscriptionStatus = 'TRIALING'
        AND s.trialEndsAt < :now
    """)
    fun findExpiredTrials(@Param("now") now: Instant): List<StudioEntity>

    @Query("""
        SELECT s FROM StudioEntity s
        WHERE s.subscriptionStatus = 'ACTIVE'
        AND s.subscriptionEndsAt < :now
    """)
    fun findExpiredSubscriptions(@Param("now") now: Instant): List<StudioEntity>
}