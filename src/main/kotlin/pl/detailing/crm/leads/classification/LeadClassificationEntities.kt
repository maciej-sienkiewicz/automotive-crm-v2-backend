package pl.detailing.crm.leads.classification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Werdykt klasyfikatora. Dwie klasy, bo decyzja jest binarna: albo z tej wiadomości
 * ma powstać lead, albo nie ma powstać nic.
 */
enum class LeadClassificationVerdict {
    /** Potencjalny klient pyta o naszą usługę — cena, termin, zakres, dostępność. */
    LEAD,

    /** Wszystko inne: spam, powiadomienia, oferty B2B kierowane DO nas, księgowość. */
    NOT_LEAD
}

/**
 * Jeden wiersz na przetworzoną wiadomość — patrz V100__auto_lead_classification.sql.
 *
 * Unikalny indeks na `message_id` jest kluczem idempotencji całej funkcji: restart
 * aplikacji, ponowny sync IMAP i druga instancja za load balancerem nie mają jak
 * zrobić z jednego maila dwóch leadów ani zapłacić dwa razy za tę samą klasyfikację.
 *
 * Wiersz powstaje TAKŻE dla wiadomości odrzuconych i tych, przy których model padł.
 * „Przyszło, ale nie zostało leadem" to pytanie, które ktoś zada — i lepiej mieć na
 * nie odpowiedź niż ciszę.
 */
@Entity
@Table(name = "lead_message_classifications")
class LeadMessageClassificationEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "message_id", nullable = false, columnDefinition = "uuid")
    val messageId: UUID,

    @Column(name = "thread_id", nullable = false, columnDefinition = "uuid")
    val threadId: UUID,

    /** CREATED | REJECTED | FAILED | SKIPPED */
    @Column(name = "status", nullable = false, length = 20)
    val status: String,

    /** null, gdy do modelu w ogóle nie doszło (odsiew wcześniej albo awaria). */
    @Column(name = "verdict", length = 20)
    val verdict: String? = null,

    @Column(name = "confidence", precision = 3, scale = 2)
    val confidence: BigDecimal? = null,

    /** Jedno zdanie modelu — do czytania przez człowieka, nigdy do logiki. */
    @Column(name = "reasoning", length = 500)
    val reasoning: String? = null,

    /** Powód po NASZEJ stronie: czemu wiersz nie skończył się leadem. */
    @Column(name = "reason", length = 300)
    val reason: String? = null,

    @Column(name = "model", length = 100)
    val model: String? = null,

    @Column(name = "lead_id", columnDefinition = "uuid")
    val leadId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

interface LeadMessageClassificationRepository : JpaRepository<LeadMessageClassificationEntity, UUID> {
    fun findByMessageId(messageId: UUID): LeadMessageClassificationEntity?

    fun existsByMessageId(messageId: UUID): Boolean
}
