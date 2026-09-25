package pl.detailing.crm.comms.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommOutboxStatus
import pl.detailing.crm.comms.domain.CommOutboxType
import pl.detailing.crm.comms.domain.CommThreadKind
import java.time.Instant
import java.util.UUID

@Repository
interface CommThreadRepository : JpaRepository<CommThreadEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommThreadEntity?

    @Query(
        """SELECT t FROM CommThreadEntity t
           WHERE t.studioId = :studioId
             AND (:accountId IS NULL OR t.accountId = :accountId)
             AND t.archived = :archived
             AND (:labelId IS NULL OR t.labelId = :labelId)
             AND (:onlyUnread = FALSE OR t.unreadCount > 0)
             AND (:onlyLeads = FALSE OR t.leadId IS NOT NULL)
             AND (:requireInbound = FALSE OR t.inboundCount > 0)
             AND (:requireOutbound = FALSE OR t.outboundCount > 0)
             AND (:screened IS NULL
                  OR (:screened = TRUE AND t.screening IS NOT NULL)
                  OR (:screened = FALSE AND t.screening IS NULL))
             AND (:query IS NULL
                  OR LOWER(t.participantEmail) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%')
                  OR LOWER(COALESCE(t.participantName, '')) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%')
                  OR LOWER(COALESCE(t.subject, '')) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%'))
           ORDER BY t.lastMessageAt DESC"""
    )
    fun search(
        @Param("studioId") studioId: UUID,
        @Param("accountId") accountId: UUID?,
        @Param("archived") archived: Boolean,
        @Param("labelId") labelId: UUID?,
        @Param("onlyUnread") onlyUnread: Boolean,
        @Param("onlyLeads") onlyLeads: Boolean,
        /** Folder Odebrane: przynajmniej jedna wiadomość od uczestnika. */
        @Param("requireInbound") requireInbound: Boolean,
        /** Folder Wysłane: przynajmniej jedna wiadomość od nas. */
        @Param("requireOutbound") requireOutbound: Boolean,
        /**
         * Werdykt automatu o zgłoszeniu z formularza: TRUE — tylko „Odrzucone" (spam,
         * testy), FALSE — bez nich (Odebrane, Wysłane), null — bez ograniczenia.
         */
        @Param("screened") screened: Boolean?,
        @Param("query") query: String?,
        pageable: Pageable
    ): Page<CommThreadEntity>

    /** Threading cascade, step 2: same normalised subject + same external participant, recent. */
    @Query(
        """SELECT t FROM CommThreadEntity t
           WHERE t.accountId = :accountId
             AND t.subjectNorm = :subjectNorm
             AND t.participantEmail = :participantEmail
             AND t.lastMessageAt >= :since
           ORDER BY t.lastMessageAt DESC"""
    )
    fun findRecentBySubjectAndParticipant(
        @Param("accountId") accountId: UUID,
        @Param("subjectNorm") subjectNorm: String,
        @Param("participantEmail") participantEmail: String,
        @Param("since") since: Instant
    ): List<CommThreadEntity>

    /**
     * Kandydaci do rozplątania: zwykłe wątki, których drugą stroną jest adres po naszej
     * stronie (skrzynka studia, robot formularza) — tak wyglądały wielkie wątki
     * formularzy sprzed V157. Jeszcze nieprzejrzane i z czym więcej niż jedną wiadomością.
     */
    @Query(
        """SELECT t FROM CommThreadEntity t
           WHERE t.accountId = :accountId
             AND t.kind = pl.detailing.crm.comms.domain.CommThreadKind.DIRECT
             AND t.untangledAt IS NULL
             AND t.messageCount >= 2
             AND t.participantEmail IN :addresses"""
    )
    fun findUntangleCandidates(
        @Param("accountId") accountId: UUID,
        @Param("addresses") addresses: Collection<String>
    ): List<CommThreadEntity>

    /** Wątek zwrotów serwera pocztowego — jeden na skrzynkę. */
    fun findFirstByAccountIdAndKindOrderByCreatedAtAsc(accountId: UUID, kind: CommThreadKind): CommThreadEntity?

    /**
     * Świeże zgłoszenie tej samej osoby przez ten sam formularz — kandydat na dopisanie
     * duplikatu (klient kliknął „Wyślij" drugi raz, bo poprawił treść).
     */
    @Query(
        """SELECT t FROM CommThreadEntity t
           WHERE t.accountId = :accountId
             AND t.kind = pl.detailing.crm.comms.domain.CommThreadKind.FORM
             AND t.participantEmail = :participantEmail
             AND t.relayEmail = :relayEmail
             AND t.lastMessageAt >= :since
           ORDER BY t.lastMessageAt DESC"""
    )
    fun findRecentFormThreads(
        @Param("accountId") accountId: UUID,
        @Param("participantEmail") participantEmail: String,
        @Param("relayEmail") relayEmail: String,
        @Param("since") since: Instant
    ): List<CommThreadEntity>

    fun findByStudioIdAndParticipantEmailOrderByLastMessageAtDesc(
        studioId: UUID,
        participantEmail: String,
        pageable: Pageable
    ): List<CommThreadEntity>

    /** Ile rozmów prowadziliśmy z tym adresem — badge w nagłówku podaje liczbę, nie listę. */
    fun countByStudioIdAndParticipantEmail(studioId: UUID, participantEmail: String): Long

    /** Odrzucone przez automat (spam, testy) nie wołają o uwagę licznikiem nieprzeczytanych. */
    @Query(
        "SELECT COALESCE(SUM(t.unreadCount), 0) FROM CommThreadEntity t " +
            "WHERE t.studioId = :studioId AND t.archived = FALSE AND t.screening IS NULL"
    )
    fun countUnread(@Param("studioId") studioId: UUID): Long
}

@Repository
interface CommMessageRepository : JpaRepository<CommMessageEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommMessageEntity?

    fun findByAccountIdAndMessageIdHdr(accountId: UUID, messageIdHdr: String): CommMessageEntity?

    fun findByThreadIdOrderBySentAtAsc(threadId: UUID): List<CommMessageEntity>

    fun findFirstByThreadIdOrderBySentAtDesc(threadId: UUID): CommMessageEntity?

    /**
     * Najnowsza wiadomość PRZYCHODZĄCA wątku - ta, którą cofa „Oznacz jako
     * nieprzeczytaną" z listy rozmów.
     *
     * Przychodząca, nie „ostatnia w ogóle": wychodząca jest przeczytana z definicji
     * i nie wchodzi do licznika nieprzeczytanych, więc cofanie jej nie miałoby czego
     * zmienić. Gdy odpisaliśmy klientowi, wracamy do jego ostatniej wiadomości.
     */
    fun findFirstByStudioIdAndThreadIdAndDirectionOrderBySentAtDesc(
        studioId: UUID,
        threadId: UUID,
        direction: pl.detailing.crm.comms.domain.CommDirection
    ): CommMessageEntity?

    /** Pytanie klienta, na które odpowiadała nasza wiadomość — para dla szkiców odpowiedzi. */
    fun findFirstByThreadIdAndDirectionAndSentAtBeforeOrderBySentAtDesc(
        threadId: UUID,
        direction: pl.detailing.crm.comms.domain.CommDirection,
        sentAt: Instant
    ): CommMessageEntity?

    /**
     * Ostatnia wiadomość przychodząca i wychodząca dla każdego z wątków — jednym
     * zapytaniem dla całej strony listy, zamiast odpytywania wątek po wątku.
     *
     * Kolumny: thread_id, last_inbound_at, last_outbound_at (obie mogą być null).
     * Z tej pary wynika, czyj jest ruch w rozmowie i jak długo już trwa.
     */
    @Query(
        """
        SELECT m.threadId,
               MAX(CASE WHEN m.direction = pl.detailing.crm.comms.domain.CommDirection.INBOUND
                        THEN m.sentAt END),
               MAX(CASE WHEN m.direction = pl.detailing.crm.comms.domain.CommDirection.OUTBOUND
                        THEN m.sentAt END)
        FROM CommMessageEntity m
        WHERE m.studioId = :studioId AND m.threadId IN :threadIds
        GROUP BY m.threadId
        """
    )
    fun findLastDirectionTimestamps(
        @Param("studioId") studioId: UUID,
        @Param("threadIds") threadIds: Collection<UUID>
    ): List<Array<Any?>>

    /**
     * Korespondencja z JEDNYM klientem wewnątrz wątku zbiorczego.
     *
     * Robot formularza ze strony wrzuca zgłoszenia wszystkich klientów do jednego
     * wątku — u jednego studia jest ich w nim 249. Lead z takiego zgłoszenia nie ma
     * więc wątku (podpięcie pokazałoby mu cudzą korespondencję), ale jego własna
     * rozmowa w tym wątku jest: to wiadomości, których DRUGĄ STRONĄ jest ten klient.
     *
     * Przychodzące poznajemy po nadawcy, wychodzące po odbiorcy — `toEmails` jest
     * listą rozdzieloną przecinkami, stąd LIKE zamiast równości.
     */
    @Query(
        """
        SELECT m FROM CommMessageEntity m
        WHERE m.studioId = :studioId
          AND m.threadId = :threadId
          AND m.sentAt >= :since
          AND (
                (m.direction = pl.detailing.crm.comms.domain.CommDirection.INBOUND
                 AND LOWER(m.fromEmail) = :contact)
             OR (m.direction = pl.detailing.crm.comms.domain.CommDirection.OUTBOUND
                 AND LOWER(m.toEmails) LIKE CONCAT('%', :contact, '%'))
          )
        ORDER BY m.sentAt ASC
        """
    )
    fun findCounterpartyMessages(
        @Param("studioId") studioId: UUID,
        @Param("threadId") threadId: UUID,
        @Param("contact") contact: String,
        @Param("since") since: Instant
    ): List<CommMessageEntity>

    /**
     * Lekki rzut tych samych wiadomości dla CAŁEJ strony listy: kierunek, strony
     * i czas, bez treści i załączników. Filtrowanie po kliencie robi wołający —
     * jedno zapytanie na stronę zamiast jednego na leada.
     */
    @Query(
        """
        SELECT m.threadId, m.direction, LOWER(m.fromEmail), LOWER(m.toEmails), m.sentAt
        FROM CommMessageEntity m
        WHERE m.studioId = :studioId AND m.threadId IN :threadIds
        """
    )
    fun findCounterpartyRows(
        @Param("studioId") studioId: UUID,
        @Param("threadIds") threadIds: Collection<UUID>
    ): List<Array<Any?>>

    /** Thread resolution by RFC 5322 ancestry: any known message with one of these ids. */
    @Query(
        """SELECT m FROM CommMessageEntity m
           WHERE m.accountId = :accountId AND m.messageIdHdr IN :messageIds
           ORDER BY m.sentAt DESC"""
    )
    fun findByAccountIdAndMessageIdHdrIn(
        @Param("accountId") accountId: UUID,
        @Param("messageIds") messageIds: Collection<String>
    ): List<CommMessageEntity>

    /**
     * The read-reconciliation working set: still-unread INBOX messages that have an IMAP
     * identity. Only these need their \Seen flag checked — a read message never goes back.
     */
    @Query(
        """SELECT m FROM CommMessageEntity m
           WHERE m.accountId = :accountId
             AND m.folderKind = :folderKind
             AND m.isRead = FALSE
             AND m.imapUid IS NOT NULL
             AND m.imapUidValidity = :uidValidity"""
    )
    fun findUnreadWithUid(
        @Param("accountId") accountId: UUID,
        @Param("folderKind") folderKind: CommFolderKind,
        @Param("uidValidity") uidValidity: Long
    ): List<CommMessageEntity>

    @Query(
        """SELECT m FROM CommMessageEntity m
           WHERE m.studioId = :studioId AND m.fromEmail = :fromEmail AND m.direction = 'INBOUND'
           ORDER BY m.sentAt DESC"""
    )
    fun findRecentInboundFrom(
        @Param("studioId") studioId: UUID,
        @Param("fromEmail") fromEmail: String,
        pageable: Pageable
    ): List<CommMessageEntity>
}

@Repository
interface CommAttachmentRepository : JpaRepository<CommAttachmentEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommAttachmentEntity?

    fun findByMessageIdAndContentId(messageId: UUID, contentId: String): CommAttachmentEntity?

    /** Pełne załączniki (z bajtami) jednej wiadomości — do zbudowania kopii MIME dla folderu Wysłane. */
    fun findByMessageId(messageId: UUID): List<CommAttachmentEntity>

    @Query(
        """SELECT new pl.detailing.crm.comms.infrastructure.CommAttachmentMeta(
                  a.id, a.messageId, a.fileName, a.contentType,
                  a.contentId, a.isInline, a.sizeBytes)
           FROM CommAttachmentEntity a WHERE a.messageId IN :messageIds"""
    )
    fun findMetaByMessageIdIn(@Param("messageIds") messageIds: Collection<UUID>): List<CommAttachmentMeta>
}

/**
 * Metadata projection — keeps attachment bytes out of message queries.
 *
 * A constructor expression (`SELECT new …`) rather than a Spring Data interface
 * projection, and deliberately so. An interface projection binds each accessor to a
 * select alias through the JavaBean naming convention, and Kotlin's `is`-prefixed
 * properties break that convention: `val isInline: Boolean` compiles to the accessor
 * `isInline()`, from which Spring derives the property name **`inline`** — so the alias
 * `isInline` never matched, the value resolved to null, and a null returned for a
 * primitive `boolean` blew up as an AopInvocationException at render time rather than
 * at query time.
 *
 * Constructor expressions bind positionally, so no naming convention sits between the
 * query and the result and the whole class of bug disappears. Any future field is a
 * compile error if the select list does not match, instead of a null at runtime.
 */
data class CommAttachmentMeta(
    val id: UUID,
    val messageId: UUID,
    val fileName: String,
    val contentType: String,
    val contentId: String?,
    val isInline: Boolean,
    val sizeBytes: Long
)

@Repository
interface CommLabelRepository : JpaRepository<CommLabelEntity, UUID> {
    fun findByStudioIdOrderByPositionAsc(studioId: UUID): List<CommLabelEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommLabelEntity?
}

@Repository
interface CommOutboxRepository : JpaRepository<CommOutboxEntity, UUID> {

    @Query(
        """SELECT o FROM CommOutboxEntity o
           WHERE o.status = 'PENDING' AND o.nextAttemptAt <= :now
           ORDER BY o.createdAt ASC"""
    )
    fun findDue(@Param("now") now: Instant, pageable: Pageable): List<CommOutboxEntity>

    @Modifying
    @Query("DELETE FROM CommOutboxEntity o WHERE o.status = :status AND o.createdAt < :before")
    fun deleteOldByStatus(@Param("status") status: CommOutboxStatus, @Param("before") before: Instant): Int

    /** Identyfikatory wiadomości z zaległą komendą danego typu — reconcile ich nie rusza. */
    @Query(
        """SELECT o.messageId FROM CommOutboxEntity o
           WHERE o.accountId = :accountId AND o.commandType = :type AND o.status = 'PENDING'"""
    )
    fun findPendingMessageIds(
        @Param("accountId") accountId: UUID,
        @Param("type") type: CommOutboxType
    ): List<UUID>
}
