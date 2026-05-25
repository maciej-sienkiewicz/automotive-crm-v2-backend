package pl.detailing.crm.demo

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface DemoAccountRepository : JpaRepository<DemoAccountEntity, UUID> {

    fun findByStudioId(studioId: UUID): DemoAccountEntity?

    @Query("SELECT d FROM DemoAccountEntity d WHERE d.expiresAt < :now")
    fun findExpired(@Param("now") now: Instant): List<DemoAccountEntity>

    @Modifying
    @Query("DELETE FROM DemoAccountEntity d WHERE d.id = :id")
    fun deleteByIdQuery(@Param("id") id: UUID)
}
