package pl.detailing.crm.visit.settlement

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import java.time.Instant
import java.util.UUID

/** Co poprawka zrobiła z fakturą KSeF wizyty. */
enum class SettlementKsefAction(val displayName: String) {
    NONE("Bez faktury KSeF"),
    KEEP("Faktura bez zmian"),
    CANCEL_AND_REISSUE("Anulowanie niewysłanej faktury i nowa faktura"),
    CANCEL("Anulowanie niewysłanej faktury"),
    CORRECT_AND_REISSUE("Korekta do zera i nowa faktura"),
    CORRECT("Korekta faktury do zera"),
    ISSUE("Nowa faktura"),
    INVOICE_TO_RECEIPT("Faktura do paragonu")
}

/**
 * Wpis historii rozliczenia wizyty: jedna poprawka po wydaniu pojazdu.
 *
 * Zapisuje stan przed i po (pozycje jako JSON, sumy, forma płatności, rodzaj dokumentu)
 * oraz to, co stało się z dokumentami — widok wizyty pokazuje z tego „Historię
 * rozliczenia", a dokumenty jednej poprawki niosą jej id (settlement_correction_id).
 */
@Entity
@Table(
    name = "visit_settlement_corrections",
    indexes = [Index(name = "idx_visit_settlement_corrections_visit", columnList = "studio_id, visit_id, created_at")]
)
class VisitSettlementCorrectionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "reason", length = 500)
    val reason: String?,

    @Column(name = "total_net_before", nullable = false)
    val totalNetBefore: Long,

    @Column(name = "total_gross_before", nullable = false)
    val totalGrossBefore: Long,

    @Column(name = "total_net_after", nullable = false)
    val totalNetAfter: Long,

    @Column(name = "total_gross_after", nullable = false)
    val totalGrossAfter: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method_before", columnDefinition = "VARCHAR(30)")
    val paymentMethodBefore: PaymentMethod?,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method_after", nullable = false, columnDefinition = "VARCHAR(30)")
    val paymentMethodAfter: PaymentMethod,

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type_before", columnDefinition = "VARCHAR(20)")
    val documentTypeBefore: DocumentType?,

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type_after", nullable = false, columnDefinition = "VARCHAR(20)")
    val documentTypeAfter: DocumentType,

    @Enumerated(EnumType.STRING)
    @Column(name = "ksef_action", nullable = false, columnDefinition = "VARCHAR(30)")
    val ksefAction: SettlementKsefAction,

    @Column(name = "cancelled_ksef_invoice_id", columnDefinition = "uuid")
    var cancelledKsefInvoiceId: UUID? = null,

    @Column(name = "ksef_correction_invoice_id", columnDefinition = "uuid")
    var ksefCorrectionInvoiceId: UUID? = null,

    @Column(name = "new_ksef_invoice_id", columnDefinition = "uuid")
    var newKsefInvoiceId: UUID? = null,

    /** Błąd kroku KSeF po zapisie poprawki — widoczny w historii, do ręcznego dokończenia. */
    @Column(name = "ksef_error", columnDefinition = "TEXT")
    var ksefError: String? = null,

    /** Zdania „co się stało", po jednym w linii — te same, które pokazał podgląd. */
    @Column(name = "steps", nullable = false, columnDefinition = "TEXT")
    val steps: String,

    @Column(name = "services_before", nullable = false, columnDefinition = "TEXT")
    val servicesBefore: String,

    @Column(name = "services_after", nullable = false, columnDefinition = "TEXT")
    val servicesAfter: String,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name")
    val createdByName: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface VisitSettlementCorrectionRepository : JpaRepository<VisitSettlementCorrectionEntity, UUID> {
    fun findByStudioIdAndVisitIdOrderByCreatedAtDesc(studioId: UUID, visitId: UUID): List<VisitSettlementCorrectionEntity>
}
