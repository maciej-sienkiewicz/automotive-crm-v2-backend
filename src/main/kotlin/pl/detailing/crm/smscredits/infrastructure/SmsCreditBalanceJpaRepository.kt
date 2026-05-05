package pl.detailing.crm.smscredits.infrastructure

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SmsCreditBalanceJpaRepository : JpaRepository<SmsCreditBalanceEntity, UUID> {

    fun findByStudioId(studioId: UUID): SmsCreditBalanceEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM SmsCreditBalanceEntity b WHERE b.studioId = :studioId")
    fun findByStudioIdForUpdate(studioId: UUID): SmsCreditBalanceEntity?
}
