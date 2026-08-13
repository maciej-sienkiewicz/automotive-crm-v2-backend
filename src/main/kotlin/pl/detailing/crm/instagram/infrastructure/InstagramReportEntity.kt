package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Wygenerowany raport tygodnia dla studia. Generowany raz (po niedzielnym syncu
 * lub leniwie przy pierwszym odczycie), potem tylko odczytywany.
 * payload = strukturalne dane sekcji (JSON) renderowane przez frontend;
 * lead = 2–3 zdania narracji (LLM albo szablon deterministyczny).
 */
@Entity
@Table(
    name = "instagram_reports",
    indexes = [
        Index(name = "idx_ig_reports_studio_period", columnList = "studio_id, period_start", unique = true),
        Index(name = "idx_ig_reports_studio", columnList = "studio_id")
    ]
)
class InstagramReportEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Poniedziałek raportowanego (zamkniętego) tygodnia. */
    @Column(name = "period_start", nullable = false)
    val periodStart: LocalDate,

    @Column(name = "period_end", nullable = false)
    val periodEnd: LocalDate,

    @Column(name = "title", nullable = false, length = 120)
    val title: String,

    @Column(name = "lead", nullable = false, columnDefinition = "text")
    val lead: String,

    /** LLM | TEMPLATE – skąd pochodzi narracja. */
    @Column(name = "narrative_source", nullable = false, length = 10)
    val narrativeSource: String,

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    val payload: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant
)
