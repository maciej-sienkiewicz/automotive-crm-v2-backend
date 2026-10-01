package pl.detailing.crm.payments.order

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Instant
import java.util.UUID

interface PaymentOrderRepository : JpaRepository<PaymentOrderEntity, UUID> {
    fun findBySessionId(sessionId: String): PaymentOrderEntity?
    fun findByIdAndStudioId(id: UUID, studioId: UUID): PaymentOrderEntity?

    /**
     * Zamówienie z blokadą wiersza (`SELECT … FOR UPDATE`). Każda zmiana stanu zamówienia
     * idzie przez tę metodę: dwie obsługi tej samej płatności (duplikat notyfikacji, worker
     * i webhook naraz) czekają na siebie zamiast obie czytać PENDING (audyt, P1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PaymentOrderEntity o WHERE o.id = :id")
    fun lockById(@Param("id") id: UUID): PaymentOrderEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PaymentOrderEntity o WHERE o.sessionId = :sessionId")
    fun lockBySessionId(@Param("sessionId") sessionId: String): PaymentOrderEntity?

    @Query("SELECT o.studioId FROM PaymentOrderEntity o WHERE o.id = :id")
    fun findStudioIdById(@Param("id") id: UUID): UUID?

    /** Otwarte zamówienie na ten sam produkt — zwracane zamiast zakładania drugiego (audyt, P7). */
    @Query("""
        SELECT o FROM PaymentOrderEntity o
        WHERE o.studioId = :studioId
          AND o.type = :type
          AND o.addOnKeysRaw = :addOnKeysRaw
          AND o.planKey = :planKey
          AND o.status IN :statuses
        ORDER BY o.createdAt DESC
    """)
    fun findOpenForProduct(
        @Param("studioId") studioId: UUID,
        @Param("type") type: PaymentOrderType,
        @Param("planKey") planKey: PlanKey,
        @Param("addOnKeysRaw") addOnKeysRaw: String,
        @Param("statuses") statuses: Collection<PaymentOrderStatus>
    ): List<PaymentOrderEntity>

    /**
     * To samo z blokadą wierszy — dla checkoutu, który je wygasza. Bez blokady równoległa
     * notyfikacja albo rekoncyliacja podbijała wersję zamówienia między odczytem a wygaszeniem,
     * a checkout kończył się błędem 500 (optimistic lock). Kolejność blokad: studio → zamówienia.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT o FROM PaymentOrderEntity o
        WHERE o.studioId = :studioId
          AND o.type = :type
          AND o.addOnKeysRaw = :addOnKeysRaw
          AND o.planKey = :planKey
          AND o.status IN :statuses
        ORDER BY o.createdAt DESC
    """)
    fun lockOpenForProduct(
        @Param("studioId") studioId: UUID,
        @Param("type") type: PaymentOrderType,
        @Param("planKey") planKey: PlanKey,
        @Param("addOnKeysRaw") addOnKeysRaw: String,
        @Param("statuses") statuses: Collection<PaymentOrderStatus>
    ): List<PaymentOrderEntity>

    /** Opłacone, ale niezrealizowane — worker ponawia realizację. */
    @Query("""
        SELECT o.id FROM PaymentOrderEntity o
        WHERE o.status = 'PAID' AND o.paidAt <= :paidBefore
        ORDER BY o.paidAt ASC
    """)
    fun findPaidUnfulfilledIds(@Param("paidBefore") paidBefore: Instant, pageable: Pageable): List<UUID>

    /**
     * Otwarte zamówienia starsze niż [createdBefore], niesprawdzane w P24 od [reconciledBefore]
     * — do sprawdzenia i ewentualnego wygaszenia.
     */
    @Query("""
        SELECT o.id FROM PaymentOrderEntity o
        WHERE o.status = 'PENDING' AND o.createdAt <= :createdBefore
          AND (o.lastReconciledAt IS NULL OR o.lastReconciledAt <= :reconciledBefore)
        ORDER BY o.lastReconciledAt ASC NULLS FIRST, o.createdAt ASC
    """)
    fun findStalePendingIds(
        @Param("createdBefore") createdBefore: Instant,
        @Param("reconciledBefore") reconciledBefore: Instant,
        pageable: Pageable
    ): List<UUID>

    /**
     * Zamówienia wygaszone, które wciąż mogą zostać opłacone (strona płatności P24 była
     * wydana): niesprawdzone ani razu (zastąpione nowszym zamówieniem, porządek V172,
     * wygaszone przy wyłączonej bramce) i — rzadziej — sprawdzone dawniej niż [reconciledBefore].
     * Spóźniona płatność nie może zależeć wyłącznie od tego, czy dotrze notyfikacja.
     */
    @Query("""
        SELECT o.id FROM PaymentOrderEntity o
        WHERE o.status = 'EXPIRED' AND o.p24Token IS NOT NULL AND o.createdAt >= :createdAfter
          AND (o.lastReconciledAt IS NULL OR o.lastReconciledAt <= :reconciledBefore)
        ORDER BY o.lastReconciledAt ASC NULLS FIRST, o.createdAt ASC
    """)
    fun findExpiredDueForCheck(
        @Param("createdAfter") createdAfter: Instant,
        @Param("reconciledBefore") reconciledBefore: Instant,
        pageable: Pageable
    ): List<UUID>
}
