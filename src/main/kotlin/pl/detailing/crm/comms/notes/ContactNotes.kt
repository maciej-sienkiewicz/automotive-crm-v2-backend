package pl.detailing.crm.comms.notes

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
 * Notatka o kontakcie — to, co warto pamiętać przy następnej odpowiedzi, a czego nie
 * widać w korespondencji: „dzwoni tylko po 16", „jeździ dwoma autami", „poprzednio
 * odpadł przez cenę".
 *
 * Kluczem jest adres e-mail, nie identyfikator klienta z kartoteki. Adres istnieje od
 * pierwszej wiadomości, kartoteka bywa dopiero potem — notatka napisana o kimś, kogo
 * jeszcze nie ma w bazie klientów, nie ma prawa zniknąć w dniu, w którym go dodamy.
 *
 * Usunięcie jest miękkie: pytanie „kto skasował tę notatkę i kiedy" jest jednym
 * z tych, na które ta funkcja ma odpowiadać, a skasowany wiersz nie odpowiada na nic.
 */
@Entity
@Table(
    name = "contact_notes",
    indexes = [Index(name = "idx_contact_notes_studio_email", columnList = "studio_id, contact_email, created_at DESC")]
)
class ContactNoteEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "contact_email", nullable = false, length = 320)
    val contactEmail: String,

    @Column(name = "body", nullable = false, columnDefinition = "text")
    var body: String,

    @Column(name = "created_by_id", columnDefinition = "uuid")
    val createdById: UUID?,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
)

/** Ślad każdej zmiany notatki: kto, kiedy, co było przed i co po. Tylko dopisywany. */
@Entity
@Table(
    name = "contact_note_events",
    indexes = [Index(name = "idx_contact_note_events_studio_email", columnList = "studio_id, contact_email, created_at DESC")]
)
class ContactNoteEventEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "contact_email", nullable = false, length = 320)
    val contactEmail: String,

    @Column(name = "note_id", nullable = false, columnDefinition = "uuid")
    val noteId: UUID,

    /** CREATED / UPDATED / DELETED — bez @Enumerated, żeby Hibernate nie dorobił CHECK-a. */
    @Column(name = "action", nullable = false, length = 20)
    val action: String,

    @Column(name = "body_before", columnDefinition = "text")
    val bodyBefore: String?,

    @Column(name = "body_after", columnDefinition = "text")
    val bodyAfter: String?,

    @Column(name = "actor_id", columnDefinition = "uuid")
    val actorId: UUID?,

    @Column(name = "actor_name", nullable = false, length = 200)
    val actorName: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface ContactNoteRepository : JpaRepository<ContactNoteEntity, UUID> {

    fun findByStudioIdAndContactEmailAndDeletedAtIsNullOrderByCreatedAtDesc(
        studioId: UUID,
        contactEmail: String
    ): List<ContactNoteEntity>

    fun countByStudioIdAndContactEmailAndDeletedAtIsNull(studioId: UUID, contactEmail: String): Long

    fun findByIdAndStudioId(id: UUID, studioId: UUID): ContactNoteEntity?
}

@Repository
interface ContactNoteEventRepository : JpaRepository<ContactNoteEventEntity, UUID> {

    fun findTop100ByStudioIdAndContactEmailOrderByCreatedAtDesc(
        studioId: UUID,
        contactEmail: String
    ): List<ContactNoteEventEntity>
}
