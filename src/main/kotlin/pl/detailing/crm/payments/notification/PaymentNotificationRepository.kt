package pl.detailing.crm.payments.notification

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PaymentNotificationRepository : JpaRepository<PaymentNotificationEntity, UUID> {

    /**
     * Zapis idempotentny: duplikat (ten sam `provider_order_id`) nie wstawia drugiego wiersza
     * i nie rzuca wyjątku. Rozstrzyga baza, nie SELECT przed INSERT-em — dwie równoległe
     * notyfikacje tej samej płatności nie mogą się tu ścigać.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO payment_notifications
                (id, provider, provider_order_id, session_id, amount_cents, currency, payload,
                 source, status, already_verified, attempts, received_at)
            VALUES
                (:id, :provider, :providerOrderId, :sessionId, :amountCents, :currency, CAST(:payload AS jsonb),
                 :source, 'RECEIVED', :alreadyVerified, 0, :receivedAt)
            ON CONFLICT (provider, provider_order_id) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("provider") provider: String,
        @Param("providerOrderId") providerOrderId: Long,
        @Param("sessionId") sessionId: String,
        @Param("amountCents") amountCents: Long,
        @Param("currency") currency: String,
        @Param("payload") payload: String,
        @Param("source") source: String,
        @Param("alreadyVerified") alreadyVerified: Boolean,
        @Param("receivedAt") receivedAt: Instant
    ): Int

    @Query("SELECT n.id FROM PaymentNotificationEntity n WHERE n.provider = :provider AND n.providerOrderId = :providerOrderId")
    fun findIdByProviderOrder(@Param("provider") provider: String, @Param("providerOrderId") providerOrderId: Long): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM PaymentNotificationEntity n WHERE n.id = :id")
    fun lockById(@Param("id") id: UUID): PaymentNotificationEntity?

    /** Do ponowienia: zapisane i niedokończone (także niedopasowane), których termin minął. */
    @Query("""
        SELECT n.id FROM PaymentNotificationEntity n
        WHERE (n.status = 'RECEIVED' OR n.status = 'UNMATCHED')
          AND (n.nextAttemptAt IS NULL OR n.nextAttemptAt <= :now)
        ORDER BY n.receivedAt ASC
    """)
    fun findDueForRetry(@Param("now") now: Instant, pageable: Pageable): List<UUID>

    fun countByStatus(status: PaymentNotificationStatus): Long
}
