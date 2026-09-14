package pl.detailing.crm.communication.queue

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import pl.detailing.crm.communication.OutboundMessageCategory
import pl.detailing.crm.shared.CommunicationChannel
import java.time.Instant
import java.util.UUID

enum class OutboundMessageStatus {
    /** Czeka na [OutboundMessageEntity.scheduledFor]. Jedyny status, z którego dispatcher zabiera wiersz. */
    QUEUED,

    /**
     * Zajęty przez dispatcher — wysyłka trwa. Wiersz, który utknął w tym statusie dłużej
     * niż [OutboundMessageQueue.STALE_SENDING_AFTER], oznacza restart w trakcie wysyłki;
     * zostaje oznaczony FAILED, a nie ponowiony, bo nie wiemy, czy dostawca zdążył go
     * dostać (dubel SMS-a kosztuje kredyt i irytuje klienta; brak jest widoczny w dzienniku).
     */
    SENDING,
    SENT,
    FAILED,
    CANCELLED
}

/**
 * Wiadomość do klienta, która czeka na godziny, w których wolno ją wysłać.
 *
 * Wiersz powstaje wyłącznie w [pl.detailing.crm.communication.OutboundCommunicationGateway],
 * gdy wysyłka wypada poza [pl.detailing.crm.communication.window.SendWindow]. Wszystko,
 * co bramka rozstrzygnęła przed odłożeniem (moduł, zgoda marketingowa), jest sprawdzane
 * PONOWNIE przy wysyłce — między 20:50 a 12:00 klient mógł cofnąć zgodę, a studio
 * stracić moduł. Adresat, przekierowanie i kredyty też są rozstrzygane dopiero przy
 * wysyłce; tu leży tylko to, co klient ma przeczytać.
 *
 * Treść jest zamrożona w chwili odłożenia: zmiana szablonu w ustawieniach nie przepisuje
 * wiadomości, która już została „wysłana" z punktu widzenia pracownika.
 */
@Entity
@Table(
    name = "outbound_message_queue",
    indexes = [
        Index(name = "idx_outbound_queue_status_scheduled", columnList = "status, scheduled_for"),
        Index(name = "idx_outbound_queue_studio", columnList = "studio_id, created_at")
    ]
)
class OutboundMessageEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Null dla wiadomości bez kartoteki klienta (zestawienie dla kontrahenta). */
    @Column(name = "customer_id", columnDefinition = "uuid")
    val customerId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    val channel: CommunicationChannel,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    val category: OutboundMessageCategory,

    /** Numer E.164 albo adres e-mail — taki, jaki podał wywołujący, PRZED przekierowaniem. */
    @Column(name = "recipient", nullable = false, length = 255)
    val recipient: String,

    @Column(name = "subject", length = 500)
    val subject: String?,

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    val body: String,

    /** Kontekst wywołania do logów i metryk — ten sam, który poszedłby do dostawcy od razu. */
    @Column(name = "context", nullable = false, length = 255)
    val context: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: OutboundMessageStatus,

    @Column(name = "scheduled_for", nullable = false, columnDefinition = "timestamp with time zone")
    var scheduledFor: Instant,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null,

    @Column(name = "external_message_id", length = 255)
    var externalMessageId: String? = null,

    @Column(name = "sent_at", columnDefinition = "timestamp with time zone")
    var sentAt: Instant? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

/**
 * Załącznik odłożonej wiadomości (PDF zestawienia dla kontrahenta). Osobna tabela, bo
 * dispatcher listuje kolejkę bez ciągnięcia bajtów, a ładuje je dopiero do wysyłki.
 */
@Entity
@Table(
    name = "outbound_message_attachment",
    indexes = [Index(name = "idx_outbound_attachment_message", columnList = "message_id")]
)
class OutboundMessageAttachmentEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "message_id", nullable = false, columnDefinition = "uuid")
    val messageId: UUID,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "content_type", nullable = false, length = 255)
    val contentType: String,

    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    val content: ByteArray
)
