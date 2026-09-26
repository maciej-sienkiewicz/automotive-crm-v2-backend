package pl.detailing.crm.visit.settlement

import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitServiceItemId
import pl.detailing.crm.visit.domain.SettledPrice
import java.time.LocalDate
import java.util.UUID

/** Nabywca faktury po poprawce. */
data class SettlementBuyer(
    val nip: String? = null,
    val name: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val email: String? = null
) {
    val normalizedNip: String? get() = nip?.replace(Regex("[^0-9]"), "")?.ifBlank { null }
}

/**
 * Poprawka rozliczenia wizyty wydanej: nowe ceny pozycji, rodzaj dokumentu, forma płatności.
 * [prices] obejmuje pozycje, które się zmieniają — brak pozycji = cena bez zmian.
 */
data class SettlementCorrectionCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val visitId: VisitId,
    val prices: Map<VisitServiceItemId, SettledPrice>,
    val documentType: DocumentType,
    val paymentMethod: PaymentMethod,
    val dueDate: LocalDate?,
    val buyer: SettlementBuyer?,
    val exemptionLegalBasis: String?,
    val reason: String?
)

/** Stan rozliczenia wizyty w chwili planowania — dokumenty i faktury, które dziś obowiązują. */
internal data class SettlementState(
    /** Dokumenty finansowe wizyty, które obowiązują: nie usunięte, nie zastąpione, nie korekty. */
    val activeDocuments: List<FinancialDocumentEntity>,
    /** Faktury KSeF wizyty, które obowiązują: nie anulowane, nie odrzucone, bez korekty. */
    val activeInvoices: List<KsefRevenueInvoiceEntity>,
    /** Rodzaj rozliczenia: faktura, jeśli jest dokument faktury; inaczej rodzaj głównego dokumentu. */
    val documentType: DocumentType?,
    val paymentMethod: PaymentMethod?
) {
    /** Faktury powiązane z dokumentem faktury (nie faktury do paragonu). */
    val invoiceDocuments: List<FinancialDocumentEntity>
        get() = activeDocuments.filter { it.documentType == DocumentType.INVOICE }
}

/** Co zrobić z jedną obowiązującą fakturą KSeF. */
internal enum class InvoiceTreatment { KEEP, CANCEL, CORRECT_TO_ZERO }

/**
 * Plan poprawki — wynik wyłącznie odczytów. Podgląd pokazuje [steps], wykonanie robi
 * dokładnie to, co tu zapisano (plan liczony drugi raz pod blokadą wiersza wizyty).
 */
internal data class SettlementPlan(
    val itemsChanged: Boolean,
    val documentsToReplace: List<FinancialDocumentEntity>,
    val invoiceTreatments: Map<UUID, InvoiceTreatment>,
    /** Nowy dokument finansowy po poprawce (paragon, inny albo dokument faktury); null = bez dokumentu. */
    val newDocumentType: DocumentType?,
    val issueInvoice: Boolean,
    val invoiceToReceipt: Boolean,
    /**
     * Zastąpione dokumenty wracają jako kopie z nową formą płatności (te same kwoty, typ
     * i powiązanie z fakturą) zamiast jednego nowego dokumentu z kwot wizyty. Tak jest,
     * gdy zmienia się tylko forma płatności: faktura zostaje, podział faktura + reszta też.
     */
    val cloneDocuments: Boolean,
    val ksefAction: SettlementKsefAction,
    val totalNetBefore: Long,
    val totalGrossBefore: Long,
    val totalNetAfter: Long,
    val totalGrossAfter: Long,
    /** Ile klient dopłaca (+) albo dostaje zwrotu (−) względem opłaconych dotąd dokumentów. */
    val customerDifference: Long,
    /** Zmiana stanu kasy (gotówka): storna gotówkowe na minus, nowe gotówkowe na plus. */
    val cashDelta: Long,
    val steps: List<String>,
    val blockReason: String?
)
