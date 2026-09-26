package pl.detailing.crm.ownerreport.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.detailing.crm.ownerreport.domain.ReportFrequency
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Czy i jak często użytkownik chce powiadomienie „Dostępny nowy raport".
 * Ustawienie należy do osoby, nie do studia — powiadomienie trafia na jej telefon.
 * Brak wiersza = [ReportFrequency.OFF].
 */
@Entity
@Table(name = "owner_report_notifications")
class OwnerReportNotificationEntity(
    @Id
    @Column(name = "user_id", columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    var frequency: ReportFrequency = ReportFrequency.OFF,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

@Repository
interface OwnerReportNotificationRepository : JpaRepository<OwnerReportNotificationEntity, UUID> {

    /**
     * Włączone powiadomienia w studiach, do których wolno coś wysłać: studio klienta
     * (nie DEMO ani piaskownica podglądu roli) z dostępem do systemu. Aktywność konta
     * i uprawnienia sprawdza wysyłka, per odbiorca.
     */
    @Query(
        value = """
            SELECT n.* FROM owner_report_notifications n
            JOIN studios st ON st.id = n.studio_id
            WHERE n.frequency <> 'OFF'
              AND st.kind = 'REGULAR'
              AND st.subscription_status IN ('TRIALING', 'ACTIVE', 'PAST_DUE')
        """,
        nativeQuery = true
    )
    fun findEnabledInActiveStudios(): List<OwnerReportNotificationEntity>
}

/**
 * Rejestr wysłanych powiadomień — jednocześnie blokada między instancjami.
 *
 * Harmonogram odpala się na każdej instancji aplikacji. Wiersz (użytkownik, okres)
 * ma klucz unikalny, więc tylko jedna instancja zdoła go wstawić i tylko ona wysyła.
 */
@Repository
class OwnerReportNotificationLog(private val jdbc: JdbcTemplate) {

    /** true = ta instancja przejęła wysyłkę tego okresu. */
    fun claim(userId: UUID, studioId: UUID, from: LocalDate, to: LocalDate): Boolean = jdbc.update(
        """
        INSERT INTO owner_report_notification_dispatches (id, user_id, studio_id, period_start, period_end)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (user_id, period_start, period_end) DO NOTHING
        """.trimIndent(),
        UUID.randomUUID(), userId, studioId, from, to
    ) == 1
}
