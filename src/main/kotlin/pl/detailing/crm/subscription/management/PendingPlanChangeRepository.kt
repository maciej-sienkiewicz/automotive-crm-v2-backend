package pl.detailing.crm.subscription.management

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PendingPlanChangeRepository : JpaRepository<PendingPlanChangeEntity, UUID> {

    fun findByStudioIdAndStatus(studioId: UUID, status: PendingPlanChangeStatus): PendingPlanChangeEntity?

    /**
     * Identyfikatory należnych downgrade'ów — same ID, bo każdy wiersz [PlanDowngradeScheduler]
     * przetwarza we własnej transakcji i wczytuje dopiero pod blokadą studia. Wczytanie encji
     * tutaj dałoby stan sprzed blokady, a na takim stanie decyzja „czy wciąż PENDING" jest
     * bezwartościowa (tak wygrywało anulowanie z przebiegiem schedulera — audyt, S2).
     */
    @Query("""
        SELECT p.id FROM PendingPlanChangeEntity p
        WHERE p.status = 'PENDING'
          AND p.effectiveAt <= :now
        ORDER BY p.effectiveAt ASC, p.id ASC
    """)
    fun findDueIds(@Param("now") now: Instant, pageable: Pageable): List<UUID>

    /** Studio wiersza bez ładowania encji — żeby zablokować studio, ZANIM wczyta się wiersz. */
    @Query("SELECT p.studioId FROM PendingPlanChangeEntity p WHERE p.id = :id")
    fun findStudioIdById(@Param("id") id: UUID): UUID?

    /**
     * Anuluje oczekujący downgrade. Wołane wyłącznie pod blokadą wiersza studia, a wersja
     * rośnie razem ze statusem — zarządzana instancja wczytana wcześniej w tej transakcji
     * nie nadpisze CANCELLED przy flushu, tylko skończy się konfliktem wersji (audyt, D6).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE PendingPlanChangeEntity p
        SET p.status = 'CANCELLED', p.version = p.version + 1
        WHERE p.studioId = :studioId AND p.status = 'PENDING'
    """)
    fun cancelPendingForStudio(@Param("studioId") studioId: UUID): Int
}
