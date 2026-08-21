package pl.detailing.crm.finance.duplicates

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Rejestr, w którym leży dokument. Rejestry są rozdzielone, więc para
 * zdublowanych dokumentów musi nieść informację, skąd pochodzi każda strona.
 */
enum class DocumentKind {
    /** financial_documents — paragon, „inne rozliczenie", faktura bez rekordu KSeF. */
    FINANCIAL_DOCUMENT,

    /** ksef_revenue_invoices — faktura sprzedażowa (wystawiona w CRM albo pobrana z KSeF). */
    KSEF_REVENUE,

    /** ksef_invoices — faktura kosztowa (pobrana z KSeF albo wpisana ręcznie). */
    KSEF_EXPENSE
}

/**
 * Jedna sprzedaż (albo zakup) udokumentowana dwa razy.
 *
 * [winnerKind]/[winnerId] to dokument, który zostaje w statystykach;
 * [loserKind]/[loserId] to ten, który przestaje się liczyć — dostaje
 * `excluded_at`, ale zostaje w bazie i na listach dokumentów.
 *
 * [dismissedAt] oznacza „to jednak dwie różne sprzedaże": wykluczenie jest
 * cofane, a para trafia na czarną listę i nie wraca.
 */
@Entity
@Table(
    name = "document_duplicate_links",
    indexes = [Index(name = "ix_ddl_studio_detected", columnList = "studio_id, detected_at")]
)
class DocumentDuplicateLinkEntity(

    @Id
    @Column(name = "id", nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "winner_kind", nullable = false, length = 24)
    val winnerKind: DocumentKind,

    @Column(name = "winner_id", nullable = false)
    val winnerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "loser_kind", nullable = false, length = 24)
    val loserKind: DocumentKind,

    @Column(name = "loser_id", nullable = false)
    val loserId: UUID,

    /** Kwota brutto, po której para się dopasowała — w groszach. */
    @Column(name = "total_gross", nullable = false)
    val totalGross: Long,

    /** Data wystawienia dokumentu wygrywającego. */
    @Column(name = "issue_date", nullable = false)
    val issueDate: LocalDate,

    @Column(name = "detected_at", nullable = false)
    val detectedAt: Instant = Instant.now(),

    @Column(name = "dismissed_at")
    var dismissedAt: Instant? = null,

    @Column(name = "dismissed_by")
    var dismissedBy: UUID? = null
) {
    val isDismissed: Boolean get() = dismissedAt != null
}
