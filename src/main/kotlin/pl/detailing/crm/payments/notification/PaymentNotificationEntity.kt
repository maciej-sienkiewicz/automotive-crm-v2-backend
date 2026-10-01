package pl.detailing.crm.payments.notification

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class PaymentNotificationSource {
    /** Notyfikacja przysłana przez P24 na urlStatus. */
    WEBHOOK,
    /** Płatność wykryta przez worker, który sam zapytał API P24 o stan transakcji. */
    RECONCILIATION
}

enum class PaymentNotificationStatus {
    /** Zapisana, czeka na (ponowną) obsługę. */
    RECEIVED,
    /** Obsłużona: zamówienie opłacone (albo to był duplikat już opłaconej płatności). */
    PROCESSED,
    /** Nie ma (jeszcze) zamówienia o tej sesji — worker próbuje dopasować ponownie. */
    UNMATCHED,
    /** Odrzucona bez weryfikacji w P24 (kwota/waluta niezgodna z zamówieniem). */
    REJECTED,
    /** Wymaga człowieka: druga płatność za opłacone zamówienie, wyczerpane ponowienia. */
    NEEDS_REVIEW
}

/**
 * Inbox notyfikacji płatności — trwały zapis KAŻDEJ notyfikacji z poprawnym podpisem,
 * zanim cokolwiek się z nią stanie.
 *
 * Wcześniej webhook trzymał notyfikację tylko w pamięci żądania: przy nieznanej sesji
 * odpowiadał 400 i nic po nim nie zostawało, a każda awaria w trakcie obsługi zdawała się
 * wyłącznie na harmonogram ponowień P24 (audyt, P5). Teraz od chwili zapisu ponowienia są
 * nasze, a unikat (provider, provider_order_id) czyni zapis idempotentnym: duplikat
 * notyfikacji — albo notyfikacja i rekoncyliacja tej samej płatności — trafia w ten sam wiersz.
 */
@Entity
@Table(
    name = "payment_notifications",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_payment_notifications_provider_order",
        columnNames = ["provider", "provider_order_id"]
    )]
)
class PaymentNotificationEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "provider", nullable = false, length = 20)
    val provider: String,

    /** `orderId` transakcji w P24 — klucz idempotencji (jeden na płatność). */
    @Column(name = "provider_order_id", nullable = false)
    val providerOrderId: Long,

    @Column(name = "session_id", nullable = false, length = 100)
    val sessionId: String,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,

    /** Surowa treść notyfikacji (albo odpowiedzi API P24) — do audytu i ponownej obróbki. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, columnDefinition = "VARCHAR(20)")
    val source: PaymentNotificationSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "VARCHAR(20)")
    var status: PaymentNotificationStatus = PaymentNotificationStatus.RECEIVED,

    /** P24 zgłosiło transakcję jako już zweryfikowaną (rekoncyliacja) — `verify` zbędny. */
    @Column(name = "already_verified", nullable = false)
    val alreadyVerified: Boolean = false,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "next_attempt_at", columnDefinition = "timestamp with time zone")
    var nextAttemptAt: Instant? = null,

    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null,

    /** Dopasowane zamówienie (gdy już wiadomo). */
    @Column(name = "order_id")
    var orderId: UUID? = null,

    /**
     * Studio dopasowanego zamówienia — nazwa `studioId` jest wymagana przez porządki
     * piaskownicy podglądu roli (`DELETE … WHERE e.studioId = :studioId`). Null, dopóki
     * notyfikacja nie jest dopasowana.
     */
    @Column(name = "studio_id")
    var studioId: UUID? = null,

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamp with time zone")
    val receivedAt: Instant,

    @Column(name = "processed_at", columnDefinition = "timestamp with time zone")
    var processedAt: Instant? = null
) {
    fun markProcessed(orderId: UUID, studioId: UUID, at: Instant) {
        status = PaymentNotificationStatus.PROCESSED
        this.orderId = orderId
        this.studioId = studioId
        processedAt = at
        nextAttemptAt = null
        lastError = null
    }

    fun markUnmatched(retryAt: Instant) {
        status = PaymentNotificationStatus.UNMATCHED
        attempts += 1
        nextAttemptAt = retryAt
    }

    fun markRejected(reason: String, orderId: UUID, studioId: UUID, at: Instant) {
        status = PaymentNotificationStatus.REJECTED
        this.orderId = orderId
        this.studioId = studioId
        lastError = reason.take(2000)
        processedAt = at
        nextAttemptAt = null
    }

    fun markNeedsReview(reason: String, at: Instant) {
        status = PaymentNotificationStatus.NEEDS_REVIEW
        lastError = reason.take(2000)
        processedAt = at
        nextAttemptAt = null
    }

    /** Notyfikacja wciąż czeka na rozstrzygnięcie (nie PROCESSED / REJECTED / NEEDS_REVIEW). */
    val isOpen: Boolean
        get() = status == PaymentNotificationStatus.RECEIVED || status == PaymentNotificationStatus.UNMATCHED

    /** Obsługa w toku do [until] — worker i ponowienie od P24 nie biorą jej w tym czasie. */
    fun lease(until: Instant) {
        nextAttemptAt = until
    }

    /** Planuje ponowienie; rozstrzygniętej notyfikacji nie cofa (spóźniona porażka równoległej obsługi). */
    fun scheduleRetry(error: String, retryAt: Instant) {
        if (!isOpen) return
        status = PaymentNotificationStatus.RECEIVED
        attempts += 1
        lastError = error.take(2000)
        nextAttemptAt = retryAt
    }

    companion object {
        const val PROVIDER_P24 = "P24"
    }
}
