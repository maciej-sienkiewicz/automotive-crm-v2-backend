package pl.detailing.crm.batchorder.infrastructure

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "batch_order_close_history",
    indexes = [Index(name = "idx_batch_close_history_studio_contractor", columnList = "studio_id, contractor_id")]
)
class BatchOrderCloseHistoryEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "contractor_id", nullable = false, columnDefinition = "uuid")
    val contractorId: UUID,

    @Column(name = "from_date", nullable = false)
    val fromDate: LocalDate,

    @Column(name = "to_date", nullable = false)
    val toDate: LocalDate,

    @Column(name = "mode", nullable = false, length = 20)
    val mode: String,

    @Column(name = "entry_count", nullable = false)
    val entryCount: Int,

    @Column(name = "total_net_cents", nullable = false)
    val totalNetCents: Long,

    @Column(name = "total_gross_cents", nullable = false)
    val totalGrossCents: Long,

    /** Ustawiane po udanej wysyłce, na tym samym rekordzie — patrz CloseMonthHandler. */
    @Column(name = "email_sent", nullable = false)
    var emailSent: Boolean = false,

    @Column(name = "email_to", length = 255)
    val emailTo: String? = null,

    @Column(name = "closed_at", nullable = false, columnDefinition = "timestamp with time zone")
    val closedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    /**
     * Pozycje w chwili rozliczenia, jako tekst JSON ([pl.detailing.crm.batchorder.report.SettlementSnapshot]).
     * Z nich powstaje PDF z historii: wpisy żyją dalej (korekta, ponowne rozliczenie
     * w trybie ALL przepina im close_history_id), a dokument, który kontrahent dostał,
     * nie może się zmieniać razem z nimi. Null przy rozliczeniach sprzed V159.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "jsonb")
    val snapshotJson: String? = null,

    /** Imię i nazwisko rozliczającego w chwili rozliczenia; null przy rekordach sprzed V159. */
    @Column(name = "closed_by_user_name", length = 255)
    val closedByUserName: String? = null
)
