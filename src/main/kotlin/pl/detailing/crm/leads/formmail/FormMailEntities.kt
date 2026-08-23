package pl.detailing.crm.leads.formmail

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Nadawca-robot formularza ze strony — patrz V83__form_mail_sources.sql.
 *
 * Kluczem dopasowania jest znormalizowany adres nadawcy, nie wątek: powiadomienia
 * formularza mają wspólny temat i wspólnego nadawcę, więc IMAP-owy wątek potrafi
 * zebrać zgłoszenia wielu różnych klientów. Decyzja „to jest formularz" dotyczy
 * adresu, a przetwarzanie — pojedynczej wiadomości.
 */
@Entity
@Table(name = "form_mail_sources")
class FormMailSourceEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Znormalizowany (lower + trim) — klucz dopasowania przychodzącej poczty. */
    @Column(name = "sender_email", nullable = false, length = 320)
    val senderEmail: String,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_by_name", length = 255)
    val createdByName: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "lead_count", nullable = false)
    var leadCount: Long = 0,

    @Column(name = "last_lead_at")
    var lastLeadAt: Instant? = null
)

interface FormMailSourceRepository : JpaRepository<FormMailSourceEntity, UUID> {
    fun findByStudioIdAndSenderEmail(studioId: UUID, senderEmail: String): FormMailSourceEntity?
    fun findByIdAndStudioId(id: UUID, studioId: UUID): FormMailSourceEntity?
    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<FormMailSourceEntity>
    fun existsByStudioIdAndSenderEmailAndActiveTrue(studioId: UUID, senderEmail: String): Boolean
}

/**
 * Jeden wiersz na przetworzony mail — dziennik i zarazem klucz idempotencji.
 * Unikalny indeks na message_id gwarantuje, że z jednego maila nigdy nie
 * powstaną dwa leady, niezależnie od tego, ile razy sync go dotknie.
 */
@Entity
@Table(name = "form_mail_extractions")
class FormMailExtractionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "source_id", nullable = false, columnDefinition = "uuid")
    val sourceId: UUID,

    @Column(name = "message_id", nullable = false, columnDefinition = "uuid")
    val messageId: UUID,

    /** CREATED | REJECTED | FAILED */
    @Column(name = "status", nullable = false, length = 20)
    val status: String,

    @Column(name = "reason", length = 300)
    val reason: String? = null,

    @Column(name = "lead_id", columnDefinition = "uuid")
    val leadId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

interface FormMailExtractionRepository : JpaRepository<FormMailExtractionEntity, UUID> {
    fun findByMessageId(messageId: UUID): FormMailExtractionEntity?

    /** Cała rozmowa naraz — podgląd wątku pyta o lead przy każdej wiadomości. */
    fun findByMessageIdIn(messageIds: Collection<UUID>): List<FormMailExtractionEntity>
}
