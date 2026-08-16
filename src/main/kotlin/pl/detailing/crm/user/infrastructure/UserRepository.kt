// src/main/kotlin/pl/detailing/crm/user/infrastructure/UserRepository.kt

package pl.detailing.crm.user.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * How many accounts hold one custom role. Lets the roles list answer "who uses
 * this?" in a single grouped query instead of one count per role.
 */
interface RoleAssignmentCount {
    val roleId: UUID
    val userCount: Long
}

@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {

    @Query("SELECT u FROM UserEntity u WHERE u.id = :id AND u.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): UserEntity?

    @Query("SELECT u FROM UserEntity u WHERE u.email = :email AND u.studioId = :studioId")
    fun findByEmailAndStudioId(
        @Param("email") email: String,
        @Param("studioId") studioId: UUID
    ): UserEntity?

    @Query("SELECT u FROM UserEntity u WHERE u.email = :email")
    fun findByEmail(@Param("email") email: String): UserEntity?

    @Query("SELECT u FROM UserEntity u WHERE u.studioId = :studioId AND u.isActive = true")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<UserEntity>

    @Query("SELECT u FROM UserEntity u WHERE u.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<UserEntity>

    @Query("SELECT COUNT(u) > 0 FROM UserEntity u WHERE u.email = :email AND u.studioId = :studioId")
    fun existsByEmailAndStudioId(
        @Param("email") email: String,
        @Param("studioId") studioId: UUID
    ): Boolean

    @Query("SELECT u FROM UserEntity u WHERE u.mobileToken = :mobileToken")
    fun findByMobileToken(@Param("mobileToken") mobileToken: String): UserEntity?

    @Query("SELECT u FROM UserEntity u WHERE u.customRoleId = :roleId AND u.studioId = :studioId")
    fun findByCustomRoleIdAndStudioId(
        @Param("roleId") roleId: UUID,
        @Param("studioId") studioId: UUID
    ): List<UserEntity>

    @Query(
        """
        SELECT u.customRoleId AS roleId, COUNT(u) AS userCount
        FROM UserEntity u
        WHERE u.studioId = :studioId AND u.customRoleId IS NOT NULL
        GROUP BY u.customRoleId
        """
    )
    fun countAssignmentsByRole(@Param("studioId") studioId: UUID): List<RoleAssignmentCount>

    /**
     * Moves every account holding [sourceRoleId] onto [targetRoleId] (null clears the
     * assignment). Runs as one statement so a role hand-over cannot leave half the team
     * on a role that is about to be deleted.
     *
     * Bulk JPQL bypasses the persistence context, hence the clear/flush — callers in the
     * same transaction must re-read affected users rather than trust loaded copies.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE UserEntity u SET u.customRoleId = :targetRoleId
        WHERE u.customRoleId = :sourceRoleId AND u.studioId = :studioId
        """
    )
    fun reassignCustomRole(
        @Param("sourceRoleId") sourceRoleId: UUID,
        @Param("targetRoleId") targetRoleId: UUID?,
        @Param("studioId") studioId: UUID
    ): Int
}