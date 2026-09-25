package pl.detailing.crm.comms.draft

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Para gotowa do zapisu; [embedding] = null dla odrzutów. */
data class NewReplyExample(
    val studioId: UUID,
    val threadId: UUID,
    val outboundMessageId: UUID,
    val inboundMessageId: UUID?,
    val eligible: Boolean,
    val inquiryText: String?,
    val replyText: String?,
    val sentAt: Instant
)

/** Para znaleziona dla bieżącej rozmowy, z odległością kosinusową jej pytania. */
data class StoredReplyExample(
    val id: UUID,
    val threadId: UUID,
    val threadSubject: String?,
    val inquiryText: String,
    val replyText: String,
    val sentAt: Instant,
    val distance: Double
)

/**
 * Natywny SQL do `comm_reply_examples` — wszystko, co dotyka kolumny `vector`.
 *
 * Wyszukiwanie jest DOKŁADNE (pełny przegląd par studia, bez HNSW) i ma pełny porządek
 * rozstrzygający remisy (odległość, potem data, potem id). Ten sam mail zawsze dostaje
 * te same przykłady w tej samej kolejności — bez tego temperatura 0 po stronie modelu
 * niczego by nie gwarantowała, bo zmieniałoby się samo wejście.
 */
@Repository
class ReplyExampleStore(private val jdbc: JdbcTemplate) {

    /**
     * Nasze wysłane wiadomości, których uzgadniacz jeszcze nie przejrzał — najnowsze
     * najpierw: bieżący styl studia jest ważniejszy niż ten sprzed trzech lat.
     * Pomija wątki zwrotów serwera i zgłoszenia odrzucone jako spam/test.
     */
    fun findPendingOutbound(studioId: UUID?, limit: Int): List<UUID> {
        val studioFilter = if (studioId != null) "AND m.studio_id = ?" else ""
        val sql = """
            SELECT m.id
            FROM comm_messages m
            JOIN comm_threads t ON t.id = m.thread_id
            WHERE m.direction = 'OUTBOUND'
              AND m.send_status = 'SENT'
              AND t.kind <> 'SYSTEM'
              AND t.screening IS NULL
              $studioFilter
              AND NOT EXISTS (SELECT 1 FROM comm_reply_examples e WHERE e.outbound_message_id = m.id)
            ORDER BY m.sent_at DESC, m.id
            LIMIT ?
        """.trimIndent()
        val args: Array<Any> = if (studioId != null) arrayOf(studioId, limit) else arrayOf(limit)
        return jdbc.queryForList(sql, UUID::class.java, *args)
    }

    /** Idempotentny zapis: ta sama wiadomość przejrzana równolegle przez dwa przebiegi to nie błąd. */
    fun insert(example: NewReplyExample, embedding: FloatArray?) {
        // Odrzut bez wektora dostaje NULL wprost w SQL: pusty parametr w CAST(? AS vector)
        // zostawiłby sterownikowi zgadywanie typu, którego ten nie zna.
        val vectorValue = if (embedding != null) "CAST(? AS vector)" else "NULL"
        val args = mutableListOf<Any?>(
            UUID.randomUUID(),
            example.studioId,
            example.threadId,
            example.outboundMessageId,
            example.inboundMessageId,
            example.eligible,
            example.inquiryText,
            example.replyText,
            Timestamp.from(example.sentAt),
            Timestamp.from(Instant.now())
        )
        embedding?.let { args += toVectorLiteral(it) }
        jdbc.update(
            """
            INSERT INTO comm_reply_examples
                (id, studio_id, thread_id, outbound_message_id, inbound_message_id, eligible,
                 inquiry_text, reply_text, sent_at, created_at, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, $vectorValue)
            ON CONFLICT (outbound_message_id) DO NOTHING
            """.trimIndent(),
            *args.toTypedArray()
        )
    }

    fun countEligible(studioId: UUID): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM comm_reply_examples WHERE studio_id = ? AND eligible = TRUE AND embedding IS NOT NULL",
            Long::class.java,
            studioId
        ) ?: 0L

    /** Ile wiadomości studio w ogóle wysłało — liczba do pytania o styl, zanim indeks się zapełni. */
    fun countSentMessages(studioId: UUID): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM comm_messages WHERE studio_id = ? AND direction = 'OUTBOUND' AND send_status = 'SENT'",
            Long::class.java,
            studioId
        ) ?: 0L

    /**
     * Pary, w których klient pytał najbliżej tego, o co pyta teraz. Bieżący wątek jest
     * wykluczony — jego historia i tak idzie do modelu w całości jako rozmowa. Złączenie
     * z `comm_messages` odsiewa pary, których wiadomość zniknęła (reset, rozplątanie wątku).
     */
    fun nearest(studioId: UUID, excludeThreadId: UUID, embedding: FloatArray, limit: Int): List<StoredReplyExample> =
        jdbc.query(
            """
            SELECT e.id, e.thread_id, t.subject, e.inquiry_text, e.reply_text, e.sent_at,
                   (e.embedding <=> CAST(? AS vector)) AS distance
            FROM comm_reply_examples e
            JOIN comm_messages m ON m.id = e.outbound_message_id
            LEFT JOIN comm_threads t ON t.id = e.thread_id
            WHERE e.studio_id = ?
              AND e.eligible = TRUE
              AND e.embedding IS NOT NULL
              AND e.thread_id <> ?
            ORDER BY distance ASC, e.sent_at DESC, e.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                StoredReplyExample(
                    id = rs.getObject("id", UUID::class.java),
                    threadId = rs.getObject("thread_id", UUID::class.java),
                    threadSubject = rs.getString("subject"),
                    inquiryText = rs.getString("inquiry_text"),
                    replyText = rs.getString("reply_text"),
                    sentAt = rs.getTimestamp("sent_at").toInstant(),
                    distance = rs.getDouble("distance")
                )
            },
            toVectorLiteral(embedding),
            studioId,
            excludeThreadId,
            limit
        )

    companion object {
        /** Zapis tekstowy pgvector: `[0.1,0.2,…]`. */
        fun toVectorLiteral(values: FloatArray): String =
            values.joinToString(separator = ",", prefix = "[", postfix = "]")
    }
}

/**
 * Kolumna wektora poza zasięgiem Hibernate'a. Na produkcji zakłada ją Flyway (V160);
 * lokalnie Flyway jest wyłączony, a `ddl-auto=update` tworzy tabelę z encji — bez
 * kolumny, której encja nie zna. Dokładamy ją tu, po starcie kontekstu (tabela już
 * wtedy istnieje). Na produkcji to no-op.
 */
@Component
class ReplyDraftSchemaInitializer(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun ensureVectorColumn() {
        runCatching {
            val tableExists = jdbc.queryForObject(
                "SELECT to_regclass('comm_reply_examples') IS NOT NULL", Boolean::class.java
            ) == true
            if (!tableExists) return
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector")
            jdbc.execute("ALTER TABLE comm_reply_examples ADD COLUMN IF NOT EXISTS embedding vector(1536)")
        }.onFailure {
            log.warn("[REPLY_DRAFT] Nie udało się zapewnić kolumny wektora comm_reply_examples: {}", it.message)
        }
    }
}
