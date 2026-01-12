// src/main/kotlin/pl/detailing/crm/user/infrastructure/UserRepository.kt

package pl.detailing.crm.user.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

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

    @Query("SELECT COUNT(u) > 0 FROM UserEntity u WHERE u.email = :email AND u.studioId = :studioId")
    fun existsByEmailAndStudioId(
        @Param("email") email: String,
        @Param("studioId") studioId: UUID
    ): Boolean
}