package pl.detailing.crm.ksef.domain

import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class KsefInvoice(
    val id: UUID,
    val studioId: StudioId,
    val ksefNumber: String,
    val invoiceNumber: String?,
    val invoicingDate: OffsetDateTime?,
    val issueDate: LocalDate?,
    val sellerNip: String?,
    val sellerName: String?,
    val buyerNip: String?,
    val buyerName: String?,
    val netAmount: Double?,
    val grossAmount: Double?,
    val vatAmount: Double?,
    val currency: String?,
    val invoiceType: String?,
    val fetchedAt: Instant,
    val direction: String,           // INCOME (SUBJECT1 - sprzedawca) | EXPENSE (SUBJECT2 - nabywca)
    val isCorrection: Boolean,       // true gdy invoiceType == FA_KOR
    val originalKsefNumber: String?, // numer korygowanej faktury (jeśli dostępny w SDK)
    val status: String,              // ACTIVE | CORRECTED | CANCELLED
    val paymentForm: PaymentForm?    // forma płatności z pełnego XML faktury; null gdy niedostępna
)
