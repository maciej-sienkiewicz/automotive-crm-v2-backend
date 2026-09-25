package pl.detailing.crm.comms.draft

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Jedna para „pytanie klienta → nasza odpowiedź" z historii poczty studia — materiał,
 * z którego szkic w trybie „w moim stylu" bierze przykłady.
 *
 * Wiersz powstaje dla KAŻDEJ przejrzanej wiadomości wychodzącej, także tej, która na
 * przykład się nie nadaje (`eligible = false`: odpowiedź dwusłowna, brak pytania przed
 * nią). Bez tego uzgadniacz brałby te same odrzuty na nowo przy każdym przebiegu.
 *
 * Wektor (`embedding vector(1536)`) celowo NIE jest zmapowany: Hibernate nie zna typu
 * pgvector, a zapis i wyszukiwanie i tak idą natywnym SQL-em ([ReplyExampleStore]).
 * Kolumnę zakłada migracja V160, a lokalnie (ddl-auto=update) [ReplyDraftSchemaInitializer].
 */
@Entity
@Table(
    name = "comm_reply_examples",
    indexes = [
        Index(name = "idx_comm_reply_examples_outbound", columnList = "outbound_message_id", unique = true),
        Index(name = "idx_comm_reply_examples_studio", columnList = "studio_id, eligible")
    ]
)
class CommReplyExampleEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "thread_id", nullable = false, columnDefinition = "uuid")
    val threadId: UUID,

    /** Nasza wiadomość — klucz idempotencji uzgadniacza. */
    @Column(name = "outbound_message_id", nullable = false, columnDefinition = "uuid")
    val outboundMessageId: UUID,

    @Column(name = "inbound_message_id", columnDefinition = "uuid")
    val inboundMessageId: UUID?,

    @Column(name = "eligible", nullable = false)
    val eligible: Boolean,

    @Column(name = "inquiry_text", columnDefinition = "text")
    val inquiryText: String?,

    @Column(name = "reply_text", columnDefinition = "text")
    val replyText: String?,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * Odpowiedź użytkownika na pytanie „pisać w moim stylu czy zaproponować treść?".
 *
 * Per użytkownik, nie per studio: to on podpisuje się pod szkicem, a w jednym studiu
 * właściciel może chcieć swojego tonu, a recepcja gotowej propozycji. Brak wiersza
 * znaczy „jeszcze nie pytaliśmy" — ekran pyta wtedy przy pierwszym kliknięciu.
 */
@Entity
@Table(name = "comm_reply_draft_preferences")
class CommReplyDraftPreferenceEntity(
    @Id
    @Column(name = "user_id", columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "use_sent_style", nullable = false)
    var useSentStyle: Boolean,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Repository
interface CommReplyDraftPreferenceRepository : JpaRepository<CommReplyDraftPreferenceEntity, UUID> {
    fun findByUserIdAndStudioId(userId: UUID, studioId: UUID): CommReplyDraftPreferenceEntity?
}
