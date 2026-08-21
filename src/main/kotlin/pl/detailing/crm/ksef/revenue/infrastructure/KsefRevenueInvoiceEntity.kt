package pl.detailing.crm.ksef.revenue.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.ksef.revenue.domain.DuplicateStatus
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Jeden ledger dokumentów przychodowych — faktury wystawione w CRM i wysłane do
 * KSeF (source=CRM) oraz faktury sprzedażowe pobrane z KSeF, wystawione poza CRM
 * (source=EXTERNAL). Klucz biznesowy: [ksefNumber] (unikalny per studio, gdy nadany);
 * faktura CRM wracająca pullem jest dopasowywana po tym numerze — nigdy nie powstaje
 * drugi rekord.
 *
 * Kwoty w groszach (Long, 1/100 PLN) — spójnie z modułem finance, nigdy Double.
 * Pola cyklu wysyłki są mutowalne (var) — encja przechodzi maszynę stanów
 * [KsefRevenueStatus] w miejscu, bez kopiowania.
 */
@Entity
@Table(name = "ksef_revenue_invoices")
class KsefRevenueInvoiceEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'CRM'")
    val source: RevenueSource = RevenueSource.CRM,

    @Enumerated(EnumType.STRING)
    @Column(name = "ksef_status", nullable = false, length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'PENDING'")
    var ksefStatus: KsefRevenueStatus = KsefRevenueStatus.PENDING,

    /** Numer własny faktury (P_2), np. FV/2026/0001. Dla EXTERNAL — numer z KSeF. */
    @Column(name = "invoice_number", nullable = false, length = 100)
    val invoiceNumber: String,

    /** Numer KSeF — nadany po przyjęciu (CRM) lub znany od razu (EXTERNAL). */
    @Column(name = "ksef_number", length = 100)
    var ksefNumber: String? = null,

    /** Numer referencyjny sesji interaktywnej, w której wysłano fakturę. */
    @Column(name = "session_reference", length = 100)
    var sessionReference: String? = null,

    /** Numer referencyjny faktury w sesji — pozwala dokończyć polling po restarcie. */
    @Column(name = "invoice_reference", length = 100)
    var invoiceReference: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'VAT'")
    val invoiceType: RevenueInvoiceType = RevenueInvoiceType.VAT,

    /** Korekta → id faktury pierwotnej w naszym ledgerze. */
    @Column(name = "original_invoice_id")
    val originalInvoiceId: UUID? = null,

    /** Korekta → numer KSeF faktury pierwotnej (element DaneFaKorygowanej). */
    @Column(name = "original_ksef_number", length = 100)
    val originalKsefNumber: String? = null,

    @Column(name = "correction_reason", length = 500)
    val correctionReason: String? = null,

    @Column(name = "issue_date", nullable = false)
    val issueDate: LocalDate,

    @Column(name = "sale_date")
    var saleDate: LocalDate? = null,

    // ── Sprzedawca: snapshot danych studia z chwili wystawienia ────────────────

    @Column(name = "seller_nip", length = 20)
    val sellerNip: String? = null,

    @Column(name = "seller_name", length = 500)
    val sellerName: String? = null,

    @Column(name = "seller_address_line1", length = 500)
    var sellerAddressLine1: String? = null,

    @Column(name = "seller_address_line2", length = 500)
    var sellerAddressLine2: String? = null,

    @Column(name = "seller_country_code", length = 2, columnDefinition = "VARCHAR(2) DEFAULT 'PL'")
    val sellerCountryCode: String = "PL",

    @Column(name = "seller_bank_account", length = 40)
    var sellerBankAccount: String? = null,

    // ── Nabywca: B2B (NIP) lub B2C (buyerNip == null → BrakID=1 w FA(3)) ──────

    @Column(name = "buyer_nip", length = 20)
    val buyerNip: String? = null,

    @Column(name = "buyer_name", length = 500)
    val buyerName: String? = null,

    @Column(name = "buyer_address_line1", length = 500)
    var buyerAddressLine1: String? = null,

    @Column(name = "buyer_address_line2", length = 500)
    var buyerAddressLine2: String? = null,

    @Column(name = "buyer_country_code", length = 2, columnDefinition = "VARCHAR(2) DEFAULT 'PL'")
    val buyerCountryCode: String = "PL",

    @Column(name = "buyer_email", length = 255)
    val buyerEmail: String? = null,

    // ── Kwoty (grosze) ─────────────────────────────────────────────────────────

    @Column(name = "total_net", nullable = false)
    val totalNet: Long = 0,

    @Column(name = "total_vat", nullable = false)
    val totalVat: Long = 0,

    @Column(name = "total_gross", nullable = false)
    val totalGross: Long = 0,

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "VARCHAR(3) DEFAULT 'PLN'")
    val currency: String = "PLN",

    // ── Płatność ───────────────────────────────────────────────────────────────

    /** PaymentForm.name (GOTOWKA / KARTA / PRZELEW / …). */
    @Column(name = "payment_form", length = 20)
    var paymentForm: String? = null,

    @Column(name = "payment_status", nullable = false, length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'PENDING'")
    var paymentStatus: String = "PENDING",

    @Column(name = "payment_due_date")
    var paymentDueDate: LocalDate? = null,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    // ── Detekcja duplikatów ────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_status", nullable = false, length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'NONE'")
    var duplicateStatus: DuplicateStatus = DuplicateStatus.NONE,

    @Column(name = "duplicate_of_id")
    var duplicateOfId: UUID? = null,

    // ── Artefakty KSeF ─────────────────────────────────────────────────────────

    /** Wygenerowany XML FA(3)/FA_KOR — dokładnie ten, który poszedł do KSeF. */
    @Column(name = "invoice_xml", columnDefinition = "TEXT")
    var invoiceXml: String? = null,

    /** UPO (XML) pobrane po przyjęciu faktury przez KSeF. */
    @Column(name = "upo_xml", columnDefinition = "TEXT")
    var upoXml: String? = null,

    /** SHA-256 (Base64) wysłanego XML — do linku weryfikacyjnego / kodu QR. */
    @Column(name = "invoice_hash", length = 100)
    var invoiceHash: String? = null,

    /**
     * Czy dokument ma komplet danych z XML (pozycje, adresy stron, płatność).
     *
     * Faktura wystawiona w CRM ma je od razu — XML budujemy sami. Faktura pobrana
     * z KSeF przychodzi najpierw jako metadane (kwoty zbiorcze i strony); XML
     * pobieramy osobnym żądaniem, objętym ostrym limitem, więc gdy limit się
     * wyczerpie, rekord zostaje z details_synced = FALSE i szczegóły uzupełnia
     * synchronizacja wsteczna w kolejnym cyklu.
     */
    @Column(name = "details_synced", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    var detailsSynced: Boolean = true,

    // ── Diagnostyka wysyłki ────────────────────────────────────────────────────

    @Column(name = "send_attempts", nullable = false)
    var sendAttempts: Int = 0,

    @Column(name = "last_send_error", length = 2000)
    var lastSendError: String? = null,

    /** Początek okna offline24 — pierwszy nieudany kontakt z KSeF. */
    @Column(name = "first_queued_at")
    var firstQueuedAt: Instant? = null,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,

    // ── Kontekst CRM ───────────────────────────────────────────────────────────

    @Column(name = "visit_id")
    val visitId: UUID? = null,

    @Column(name = "customer_id")
    val customerId: UUID? = null,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String? = null,

    @Column(name = "note", columnDefinition = "TEXT")
    var note: String? = null,

    // ── Ukrycie ze statystyk ───────────────────────────────────────────────────

    /**
     * Znacznik ukrycia dokumentu przez użytkownika (odpowiednik statusu EXCLUDED
     * po stronie dokumentów kosztowych). Rekord zostaje w bazie — ledger KSeF musi
     * pozostać kompletny — ale wypada ze statystyk i z domyślnej listy.
     */
    @Column(name = "excluded_at")
    var excludedAt: Instant? = null,

    @Column(name = "excluded_by")
    var excludedBy: UUID? = null,

    @Column(name = "created_by")
    val createdBy: UUID? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    /**
     * Czy dokument da się pokazać w całości. Faktura wystawiona w CRM ma komplet
     * z definicji — XML i pozycje powstają razem z nią — niezależnie od tego, co
     * mówi flaga sterująca pobieraniem XML z KSeF.
     */
    val hasCompleteDetails: Boolean get() = detailsSynced || source == RevenueSource.CRM

    /** Ukryta ręcznie przez użytkownika — poza statystykami i domyślną listą. */
    val isExcluded: Boolean get() = excludedAt != null

    /**
     * Faktura liczy się do statystyk, o ile nie została potwierdzona jako duplikat
     * ani ręcznie ukryta.
     */
    fun countsTowardsStatistics(): Boolean =
        duplicateStatus != DuplicateStatus.CONFIRMED_DUPLICATE && !isExcluded

    /**
     * Uzupełnia fakturę EXTERNAL danymi z XML pobranego z KSeF. Nadpisujemy wyłącznie
     * puste pola: metadane bywają dokładniejsze dla stron (znormalizowany NIP), a pola
     * sterowane przez użytkownika — status płatności, notatka, ukrycie — nie są
     * przedmiotem synchronizacji i muszą przetrwać każdy backfill.
     */
    fun applyXmlDetails(
        saleDate: LocalDate?,
        sellerAddressLine1: String?,
        sellerAddressLine2: String?,
        buyerAddressLine1: String?,
        buyerAddressLine2: String?,
        bankAccount: String?,
        paymentForm: String?,
        paymentDueDate: LocalDate?,
        now: Instant = Instant.now()
    ) {
        this.saleDate           = this.saleDate ?: saleDate
        this.sellerAddressLine1 = this.sellerAddressLine1 ?: sellerAddressLine1
        this.sellerAddressLine2 = this.sellerAddressLine2 ?: sellerAddressLine2
        this.buyerAddressLine1  = this.buyerAddressLine1 ?: buyerAddressLine1
        this.buyerAddressLine2  = this.buyerAddressLine2 ?: buyerAddressLine2
        this.sellerBankAccount  = this.sellerBankAccount ?: bankAccount
        this.paymentForm        = this.paymentForm ?: paymentForm
        this.paymentDueDate     = this.paymentDueDate ?: paymentDueDate
        this.detailsSynced      = true
        this.updatedAt          = now
    }

    fun markExcluded(userId: UUID?, now: Instant = Instant.now()) {
        excludedAt = now
        excludedBy = userId
        updatedAt = now
    }

    fun markRestored(now: Instant = Instant.now()) {
        excludedAt = null
        excludedBy = null
        updatedAt = now
    }

    fun markSending(now: Instant = Instant.now()) {
        ksefStatus = KsefRevenueStatus.SENDING
        sendAttempts += 1
        updatedAt = now
    }

    fun markSubmitted(sessionRef: String, invoiceRef: String, hash: String, now: Instant = Instant.now()) {
        ksefStatus = KsefRevenueStatus.SUBMITTED
        sessionReference = sessionRef
        invoiceReference = invoiceRef
        invoiceHash = hash
        sentAt = now
        lastSendError = null
        updatedAt = now
    }

    fun markAccepted(number: String, now: Instant = Instant.now()) {
        ksefStatus = KsefRevenueStatus.ACCEPTED
        ksefNumber = number
        acceptedAt = now
        lastSendError = null
        updatedAt = now
    }

    fun markRejected(error: String, now: Instant = Instant.now()) {
        ksefStatus = KsefRevenueStatus.REJECTED
        lastSendError = error.take(2000)
        updatedAt = now
    }

    /**
     * Faktura wystawiona bez wysyłki do KSeF (świadoma decyzja użytkownika).
     * Nie zapisujemy tu błędu: nic się nie zepsuło, więc [lastSendError] zostaje puste,
     * a lista dokumentów nie pokazuje ostrzeżenia obok dokumentu, który jest w porządku.
     */
    fun markNotSent(now: Instant = Instant.now()) {
        ksefStatus = KsefRevenueStatus.NOT_SENT
        lastSendError = null
        updatedAt = now
    }

    fun markQueuedForRetry(error: String, now: Instant = Instant.now()) {
        ksefStatus = KsefRevenueStatus.QUEUED_RETRY
        lastSendError = error.take(2000)
        if (firstQueuedAt == null) firstQueuedAt = now
        updatedAt = now
    }
}
