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

/** Czy i jak często studio dostaje raport mailem. Brak wiersza = [ReportFrequency.OFF]. */
@Entity
@Table(name = "owner_report_settings")
class OwnerReportSettingsEntity(
    @Id
    @Column(name = "studio_id", columnDefinition = "uuid")
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    var frequency: ReportFrequency = ReportFrequency.OFF,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

@Repository
interface OwnerReportSettingsRepository : JpaRepository<OwnerReportSettingsEntity, UUID> {

    /**
     * Studia, którym raport w ogóle może wyjść: włączona wysyłka, studio klienta
     * (nie DEMO ani piaskownica podglądu roli — z nich nic nie wychodzi na zewnątrz)
     * i dostęp do systemu (wygasła subskrypcja nie dostaje raportów z danych,
     * do których sama nie ma wglądu).
     */
    @Query(
        value = """
            SELECT s.* FROM owner_report_settings s
            JOIN studios st ON st.id = s.studio_id
            WHERE s.frequency <> 'OFF'
              AND st.kind = 'REGULAR'
              AND st.subscription_status IN ('TRIALING', 'ACTIVE', 'PAST_DUE')
        """,
        nativeQuery = true
    )
    fun findEnabledForActiveStudios(): List<OwnerReportSettingsEntity>
}

/**
 * Rejestr wysłanych raportów — jednocześnie blokada między instancjami.
 *
 * Harmonogram odpala się na każdej instancji aplikacji. Wiersz (studio, okres) ma
 * klucz unikalny, więc tylko jedna instancja zdoła go wstawić i tylko ona wysyła.
 * Właściciel dostaje raport raz, niezależnie od liczby instancji.
 */
@Repository
class OwnerReportDispatchLog(private val jdbc: JdbcTemplate) {

    /** true = ta instancja przejęła wysyłkę tego okresu. */
    fun claim(studioId: UUID, from: LocalDate, to: LocalDate): Boolean = jdbc.update(
        """
        INSERT INTO owner_report_dispatches (id, studio_id, period_start, period_end)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (studio_id, period_start, period_end) DO NOTHING
        """.trimIndent(),
        UUID.randomUUID(), studioId, from, to
    ) == 1

    fun recordRecipients(studioId: UUID, from: LocalDate, to: LocalDate, recipients: Int) {
        jdbc.update(
            "UPDATE owner_report_dispatches SET recipients = ? WHERE studio_id = ? AND period_start = ? AND period_end = ?",
            recipients, studioId, from, to
        )
    }

    /** Zwolnienie blokady po nieudanej wysyłce — następne uruchomienie spróbuje jeszcze raz. */
    fun release(studioId: UUID, from: LocalDate, to: LocalDate) {
        jdbc.update(
            "DELETE FROM owner_report_dispatches WHERE studio_id = ? AND period_start = ? AND period_end = ?",
            studioId, from, to
        )
    }
}
