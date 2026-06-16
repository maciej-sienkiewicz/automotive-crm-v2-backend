package pl.detailing.crm.role.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface RoleRepository : JpaRepository<RoleEntity, UUID> {

    @Query("SELECT r FROM RoleEntity r WHERE r.id = :id AND r.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): RoleEntity?

    @Query("SELECT r FROM RoleEntity r WHERE r.studioId = :studioId ORDER BY r.name")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<RoleEntity>

    @Query("SELECT COUNT(r) > 0 FROM RoleEntity r WHERE r.studioId = :studioId AND r.name = :name AND r.id != :excludeId")
    fun existsByStudioIdAndNameExcluding(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String,
        @Param("excludeId") excludeId: UUID
    ): Boolean

    @Query("SELECT COUNT(r) > 0 FROM RoleEntity r WHERE r.studioId = :studioId AND r.name = :name")
    fun existsByStudioIdAndName(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String
    ): Boolean
}
