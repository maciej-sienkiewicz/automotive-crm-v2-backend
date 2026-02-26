package pl.detailing.crm.finance.infrastructure

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CashRegisterRepository : JpaRepository<CashRegisterEntity, UUID> {

    fun findByStudioId(studioId: UUID): CashRegisterEntity?

    /**
     * Acquires a pessimistic write lock on the cash register row for [studioId].
     *
     * Use this method whenever the balance is about to be modified to prevent
     * concurrent updates from producing an incorrect final balance.
     *
     * Must be called within an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CashRegisterEntity c WHERE c.studioId = :studioId")
    fun findByStudioIdForUpdate(studioId: UUID): CashRegisterEntity?
}
