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
    val buyerNip: String?,
    val netAmount: Double?,
    val grossAmount: Double?,
    val vatAmount: Double?,
    val currency: String?,
    val invoiceType: String?,
    val fetchedAt: Instant
)
