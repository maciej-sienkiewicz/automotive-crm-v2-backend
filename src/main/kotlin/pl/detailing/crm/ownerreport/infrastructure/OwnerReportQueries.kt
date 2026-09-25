package pl.detailing.crm.ownerreport.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.detailing.crm.ownerreport.domain.BatchMetrics
import pl.detailing.crm.ownerreport.domain.InstagramMetrics
import pl.detailing.crm.ownerreport.domain.UpsellMetrics
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Zapytania zliczające raportu właściciela.
 *
 * Każde to jedno COUNT/SUM po indeksowanej kolumnie daty — raport liczy się
 * w tle dla wszystkich studiów naraz i nie może ciągnąć całych tabel do pamięci.
 * Kwoty to sumy kolumn zapisanych w bazie, bez żadnego przeliczania netto ↔ brutto.
 */
@Repository
class OwnerReportQueries(private val jdbc: JdbcTemplate) {

    fun countVisitsStarted(studioId: UUID, from: Instant, to: Instant): Int = count(
        """
        SELECT COUNT(*) FROM visits
        WHERE studio_id = ? AND started_at >= ? AND started_at < ? AND deleted_at IS NULL
        """,
        studioId, ts(from), ts(to)
    )

    /**
     * Rezerwacje utworzone w okresie. Seria cykliczna (np. mycie co tydzień przez
     * kwartał) powstaje jednym kliknięciem jako wiele wierszy — liczymy ją raz, bo
     * „stworzyliśmy 14 rezerwacji" przy jednym stałym kliencie mówi nieprawdę.
     */
    fun countReservationsCreated(studioId: UUID, from: Instant, to: Instant): Int = count(
        """
        SELECT COUNT(DISTINCT COALESCE(recurrence_series_id, id)) FROM appointments
        WHERE studio_id = ? AND created_at >= ? AND created_at < ? AND deleted_at IS NULL
        """,
        studioId, ts(from), ts(to)
    )

    /**
     * Maile napisane przez ludzi ze skrzynki studia. Wątek SYSTEM to zwroty serwera
     * pocztowego, nie korespondencja.
     */
    fun countTeamEmailsSent(studioId: UUID, from: Instant, to: Instant): Int = count(
        """
        SELECT COUNT(*) FROM comm_messages m
        JOIN comm_threads t ON t.id = m.thread_id
        WHERE m.studio_id = ? AND m.direction = 'OUTBOUND' AND m.send_status = 'SENT'
          AND t.kind <> 'SYSTEM'
          AND m.sent_at >= ? AND m.sent_at < ?
        """,
        studioId, ts(from), ts(to)
    )

    /** Maile wysłane automatycznie przez CRM (powiadomienia, karty wizyt, kampanie). */
    fun countAutomatedEmailsSent(studioId: UUID, from: Instant, to: Instant): Int = count(
        """
        SELECT COUNT(*) FROM communication_log
        WHERE studio_id = ? AND channel = 'EMAIL' AND status = 'SENT'
          AND sent_at >= ? AND sent_at < ?
        """,
        studioId, ts(from), ts(to)
    )

    /**
     * Czas czekania klienta na odpowiedź, w minutach, dla każdej tury klienta
     * rozpoczętej w [from, to); null = do chwili generowania raportu bez odpowiedzi.
     *
     * Tura zaczyna się od wiadomości przychodzącej, przed którą w wątku nie ma nic
     * albo jest nasza odpowiedź. Kończy ją pierwsza nasza wiadomość po niej.
     *
     * Liczymy tylko wątki, które są rozmową z klientem: zgłoszenie z formularza, wątek
     * przypięty do leada albo taki, w którym ktoś ze studia kiedykolwiek odpisał.
     * Newslettery i powiadomienia z cudzych systemów trafiają do tej samej skrzynki,
     * nikt na nie nie odpisuje i nie powinien — bez tego filtra każdy z nich byłby
     * „klientem bez odpowiedzi". Spam i testy odrzucone przez automat też odpadają.
     */
    fun customerWaitMinutes(studioId: UUID, from: Instant, to: Instant): List<Long?> = jdbc.query(
        """
        WITH conversation AS (
            SELECT t.id FROM comm_threads t
            WHERE t.studio_id = ?
              AND t.kind <> 'SYSTEM'
              AND t.screening IS NULL
              AND (t.kind = 'FORM' OR t.lead_id IS NOT NULL OR t.outbound_count > 0)
              AND EXISTS (
                  SELECT 1 FROM comm_messages i
                  WHERE i.thread_id = t.id AND i.direction = 'INBOUND'
                    AND i.sent_at >= ? AND i.sent_at < ?
              )
        ),
        ordered AS (
            SELECT m.thread_id, m.direction, m.sent_at,
                   LAG(m.direction) OVER (PARTITION BY m.thread_id ORDER BY m.sent_at, m.id) AS prev_direction
            FROM comm_messages m
            JOIN conversation c ON c.id = m.thread_id
            WHERE m.send_status <> 'FAILED'
        ),
        turns AS (
            SELECT thread_id, sent_at AS asked_at
            FROM ordered
            WHERE direction = 'INBOUND'
              AND (prev_direction IS NULL OR prev_direction = 'OUTBOUND')
              AND sent_at >= ? AND sent_at < ?
        )
        SELECT EXTRACT(EPOCH FROM (
                   (SELECT MIN(r.sent_at) FROM comm_messages r
                    WHERE r.thread_id = turns.thread_id
                      AND r.direction = 'OUTBOUND' AND r.send_status = 'SENT'
                      AND r.sent_at > turns.asked_at)
                   - turns.asked_at
               )) / 60 AS wait_minutes
        FROM turns
        """,
        { rs, _ -> rs.getDouble("wait_minutes").takeUnless { rs.wasNull() }?.toLong() },
        studioId, ts(from), ts(to), ts(from), ts(to)
    )

    /**
     * Propozycje upsellu przedstawione w okresie i te, które klient potwierdził w okresie.
     * Kwoty to zamrożone brutto propozycji (`final_price_gross`), pokazane klientowi co do grosza.
     */
    fun upsell(studioId: UUID, from: Instant, to: Instant): UpsellMetrics {
        val suggested = jdbc.queryForMap(
            """
            SELECT COUNT(*) AS n,
                   COALESCE(SUM(final_price_gross), 0) AS gross,
                   COUNT(DISTINCT COALESCE(visit_id, appointment_id)) AS targets
            FROM visit_upsell_suggestions
            WHERE studio_id = ? AND created_at >= ? AND created_at < ?
            """,
            studioId, ts(from), ts(to)
        )
        val accepted = jdbc.queryForMap(
            """
            SELECT COUNT(*) AS n, COALESCE(SUM(final_price_gross), 0) AS gross
            FROM visit_upsell_suggestions
            WHERE studio_id = ? AND status = 'CONFIRMED'
              AND confirmed_at >= ? AND confirmed_at < ?
            """,
            studioId, ts(from), ts(to)
        )
        return UpsellMetrics(
            suggested = int(suggested["n"]),
            suggestedGrossCents = long(suggested["gross"]),
            visitsWithSuggestions = int(suggested["targets"]),
            accepted = int(accepted["n"]),
            acceptedGrossCents = long(accepted["gross"])
        )
    }

    /**
     * Ile rezerwacji/wizyt dostało link do Karty Wizyty. To jedna karta na rezerwację:
     * link wysłany przy rezerwacji i ponownie przy przyjęciu auta to ta sama karta,
     * więc liczymy po rezerwacji, do której należy wizyta, a nie po wysyłkach.
     */
    fun countVisitCardsSent(studioId: UUID, from: Instant, to: Instant): Int = count(
        """
        SELECT COUNT(DISTINCT COALESCE(cl.appointment_id, v.appointment_id, cl.visit_id))
        FROM communication_log cl
        LEFT JOIN visits v ON v.id = cl.visit_id
        WHERE cl.studio_id = ? AND cl.status = 'SENT'
          AND cl.message_type IN ('VISIT_CARD_EMAIL', 'VISIT_CARD_SMS')
          AND cl.sent_at >= ? AND cl.sent_at < ?
        """,
        studioId, ts(from), ts(to)
    )

    /** Auta obsłużone w zleceniach zbiorczych: data usługi w okresie [from, to] (dni włącznie). */
    fun batch(studioId: UUID, from: LocalDate, to: LocalDate): BatchMetrics {
        val row = jdbc.queryForMap(
            """
            SELECT COUNT(DISTINCT e.id) AS vehicles,
                   COALESCE(SUM(s.gross_amount_cents), 0) AS gross,
                   COUNT(DISTINCT e.contractor_id) AS contractors
            FROM batch_order_entries e
            LEFT JOIN batch_order_entry_services s ON s.entry_id = e.id
            WHERE e.studio_id = ? AND e.service_date >= ? AND e.service_date <= ?
            """,
            studioId, from, to
        )
        return BatchMetrics(int(row["vehicles"]), long(row["gross"]), int(row["contractors"]))
    }

    /** Wykonane, a jeszcze nierozliczone z kontrahentem — stan na teraz. */
    fun batchUnsettled(studioId: UUID): Pair<Int, Long> {
        val row = jdbc.queryForMap(
            """
            SELECT COUNT(DISTINCT e.id) AS vehicles, COALESCE(SUM(s.gross_amount_cents), 0) AS gross
            FROM batch_order_entries e
            LEFT JOIN batch_order_entry_services s ON s.entry_id = e.id
            WHERE e.studio_id = ? AND e.is_closed = FALSE
            """,
            studioId
        )
        return int(row["vehicles"]) to long(row["gross"])
    }

    /** Posty na własnym profilu studia; null, gdy studio go nie wskazało. */
    fun instagram(studioId: UUID, from: Instant, to: Instant): InstagramMetrics? {
        val profileId = jdbc.query(
            "SELECT profile_id FROM studio_instagram_profiles WHERE studio_id = ? AND is_self = TRUE LIMIT 1",
            { rs, _ -> rs.getObject("profile_id", UUID::class.java) },
            studioId
        ).firstOrNull() ?: return null
        val row = jdbc.queryForMap(
            """
            SELECT COUNT(*) AS posts,
                   COALESCE(SUM(like_count), 0) AS likes,
                   COALESCE(SUM(comment_count), 0) AS comments
            FROM instagram_post_snapshots
            WHERE profile_id = ? AND taken_at >= ? AND taken_at < ?
            """,
            profileId, ts(from), ts(to)
        )
        return InstagramMetrics(int(row["posts"]), long(row["likes"]), long(row["comments"]))
    }

    private fun count(sql: String, vararg args: Any): Int =
        (jdbc.queryForObject(sql.trimIndent(), Long::class.java, *args) ?: 0L).toInt()

    private fun ts(instant: Instant) = Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS))

    private fun int(value: Any?): Int = (value as? Number)?.toInt() ?: 0

    private fun long(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
}
