package pl.detailing.crm.visit.settlement

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitServiceItemId
import pl.detailing.crm.shared.pii.Pii
import pl.detailing.crm.visit.domain.SettledPrice
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Poprawka rozliczenia wizyty wydanej: stan, podgląd skutków i wykonanie.
 *
 * GET /api/visits/{id}/settlement              — dokumenty, faktury, pozycje, historia
 * POST /api/visits/{id}/settlement/preview      — co się stanie (bez zmian w danych)
 * POST /api/visits/{id}/settlement/corrections  — wykonanie
 */
@RestController
@RequestMapping("/api/visits")
@RequiresPermission(Permission.FINANCE_CORRECT_SETTLEMENT)
class VisitSettlementController(
    private val service: SettlementCorrectionService,
    private val documentRepository: FinancialDocumentRepository,
    private val invoiceRepository: KsefRevenueInvoiceRepository,
    private val correctionRepository: VisitSettlementCorrectionRepository
) {

    @GetMapping("/{visitId}/settlement")
    fun getSettlement(@PathVariable visitId: String): ResponseEntity<SettlementViewResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value
        val id = VisitId.fromString(visitId)
        val visit = service.loadVisit(principal.studioId, id)
        val state = service.loadState(studioId, id.value)
        val activeIds = state.activeDocuments.map { it.id }.toSet()
        val activeInvoiceIds = state.activeInvoices.map { it.id }.toSet()
        val lastInvoice = state.activeInvoices.lastOrNull()

        val documents = documentRepository.findAllByVisitIdAndStudioIdAndDeletedAtIsNull(id.value, studioId)
            .sortedBy { it.createdAt }
            .map {
                SettlementDocumentResponse(
                    id = it.id.toString(),
                    number = it.documentNumber,
                    type = it.documentType.name,
                    typeLabel = it.documentType.displayName,
                    paymentMethod = it.paymentMethod.name,
                    paymentMethodLabel = it.paymentMethod.displayName,
                    totalGross = it.totalGross,
                    status = it.status.name,
                    issueDate = it.issueDate.toString(),
                    active = it.id in activeIds,
                    superseded = it.supersededAt != null,
                    ksefInvoiceId = it.ksefRevenueInvoiceId?.toString()
                )
            }
        val invoices = invoiceRepository.findByStudioIdAndVisitIdOrderByCreatedAtAsc(studioId, id.value).map {
            SettlementInvoiceResponse(
                id = it.id.toString(),
                number = it.invoiceNumber,
                ksefNumber = it.ksefNumber,
                type = it.invoiceType.name,
                status = it.ksefStatus.name,
                invoiceToReceipt = it.invoiceToReceipt,
                totalGross = it.totalGross,
                active = it.id in activeInvoiceIds,
                buyerNip = it.buyerNip,
                buyerName = it.buyerName
            )
        }
        val services = visit.serviceItems.filter { it.countsTowardSettlement }.map {
            SettlementServiceResponse(
                id = it.id.value.toString(),
                name = it.serviceName,
                vatRate = it.vatRate.rate,
                netCents = it.finalPriceNet.amountInCents,
                grossCents = it.finalPriceGross.amountInCents,
                // Brutto ustalone przez człowieka idzie do formularza jako strona wpisana.
                grossTyped = it.basePriceGross != null
            )
        }
        val history = correctionRepository.findByStudioIdAndVisitIdOrderByCreatedAtDesc(studioId, id.value).map {
            SettlementHistoryResponse(
                id = it.id.toString(),
                createdAt = it.createdAt,
                createdByName = it.createdByName,
                reason = it.reason,
                totalGrossBefore = it.totalGrossBefore,
                totalGrossAfter = it.totalGrossAfter,
                paymentMethodBefore = it.paymentMethodBefore?.displayName,
                paymentMethodAfter = it.paymentMethodAfter.displayName,
                documentTypeBefore = it.documentTypeBefore?.displayName,
                documentTypeAfter = it.documentTypeAfter.displayName,
                ksefAction = it.ksefAction.displayName,
                ksefError = it.ksefError,
                steps = it.steps.split("\n").filter { s -> s.isNotBlank() }
            )
        }

        return ResponseEntity.ok(
            SettlementViewResponse(
                visitId = id.value.toString(),
                visitStatus = visit.status.name,
                totalNet = visit.calculateTotalNet().amountInCents,
                totalGross = visit.calculateTotalGross().amountInCents,
                documentType = state.documentType?.name,
                paymentMethod = state.paymentMethod?.name,
                buyer = lastInvoice?.let {
                    SettlementBuyerDto(it.buyerNip, it.buyerName, it.buyerAddressLine1, it.buyerAddressLine2, it.buyerEmail)
                },
                services = services,
                documents = documents,
                invoices = invoices,
                history = history
            )
        )
    }

    @PostMapping("/{visitId}/settlement/preview")
    fun preview(
        @PathVariable visitId: String,
        @RequestBody request: SettlementCorrectionRequest
    ): ResponseEntity<SettlementPreview> =
        ResponseEntity.ok(service.preview(request.toCommand(visitId)))

    @PostMapping("/{visitId}/settlement/corrections")
    fun correct(
        @PathVariable visitId: String,
        @RequestBody request: SettlementCorrectionRequest
    ): ResponseEntity<SettlementCorrectionResult> = runBlocking {
        ResponseEntity.ok(service.execute(request.toCommand(visitId)))
    }

    private fun SettlementCorrectionRequest.toCommand(visitId: String): SettlementCorrectionCommand {
        val principal = SecurityContextHelper.getCurrentUser()
        return SettlementCorrectionCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            visitId = VisitId.fromString(visitId),
            prices = services.associate { line ->
                VisitServiceItemId(UUID.fromString(line.serviceLineItemId)) to SettledPrice(
                    net = line.netCents,
                    gross = line.grossCents,
                    vatRate = runCatching { VatRate.fromInt(line.vatRate) }
                        .getOrElse { throw ValidationException("Nieprawidłowa stawka VAT: ${line.vatRate}") }
                )
            },
            documentType = parseEnum(documentType, "documentType"),
            paymentMethod = parseEnum(paymentMethod, "paymentMethod"),
            dueDate = dueDate,
            buyer = buyer?.let { SettlementBuyer(it.nip, it.name, it.addressLine1, it.addressLine2, it.email) },
            exemptionLegalBasis = exemptionLegalBasis?.trim()?.ifBlank { null },
            reason = reason
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, field: String): T =
        runCatching { enumValueOf<T>(value.uppercase()) }.getOrElse {
            throw ValidationException("Nieprawidłowa wartość '$value' dla '$field'")
        }
}

// ── DTO ────────────────────────────────────────────────────────────────────────

data class SettlementServiceLineRequest(
    val serviceLineItemId: String,
    /** Netto w groszach. */
    val netCents: Long,
    /** Brutto w groszach — tylko gdy człowiek wpisał cenę od strony brutto (CLAUDE.md §1). */
    val grossCents: Long? = null,
    /** 23 | 8 | 5 | 0 | -1 (zw.) */
    val vatRate: Int
)

data class SettlementBuyerDto(
    @Pii val nip: String? = null,
    @Pii val name: String? = null,
    @Pii val addressLine1: String? = null,
    @Pii val addressLine2: String? = null,
    @Pii val email: String? = null
)

data class SettlementCorrectionRequest(
    /** Pozycje, które się zmieniają; pominięta pozycja zostaje bez zmian. */
    val services: List<SettlementServiceLineRequest> = emptyList(),
    val documentType: String,
    val paymentMethod: String,
    val dueDate: LocalDate? = null,
    val buyer: SettlementBuyerDto? = null,
    val exemptionLegalBasis: String? = null,
    val reason: String? = null
)

data class SettlementServiceResponse(
    val id: String,
    val name: String,
    val vatRate: Int,
    val netCents: Long,
    val grossCents: Long,
    val grossTyped: Boolean
)

data class SettlementDocumentResponse(
    val id: String,
    val number: String,
    val type: String,
    val typeLabel: String,
    val paymentMethod: String,
    val paymentMethodLabel: String,
    val totalGross: Long,
    val status: String,
    val issueDate: String,
    /** Obowiązuje — to ten dokument poprawka zastąpi. */
    val active: Boolean,
    val superseded: Boolean,
    val ksefInvoiceId: String?
)

data class SettlementInvoiceResponse(
    val id: String,
    val number: String,
    val ksefNumber: String?,
    val type: String,
    val status: String,
    val invoiceToReceipt: Boolean,
    val totalGross: Long,
    val active: Boolean,
    @Pii val buyerNip: String?,
    @Pii val buyerName: String?
)

data class SettlementHistoryResponse(
    val id: String,
    val createdAt: Instant,
    val createdByName: String?,
    val reason: String?,
    val totalGrossBefore: Long,
    val totalGrossAfter: Long,
    val paymentMethodBefore: String?,
    val paymentMethodAfter: String,
    val documentTypeBefore: String?,
    val documentTypeAfter: String,
    val ksefAction: String,
    val ksefError: String?,
    val steps: List<String>
)

data class SettlementViewResponse(
    val visitId: String,
    val visitStatus: String,
    val totalNet: Long,
    val totalGross: Long,
    /** Obecny rodzaj rozliczenia (RECEIPT | INVOICE | OTHER); null = wizyta bez dokumentu. */
    val documentType: String?,
    val paymentMethod: String?,
    val buyer: SettlementBuyerDto?,
    val services: List<SettlementServiceResponse>,
    val documents: List<SettlementDocumentResponse>,
    val invoices: List<SettlementInvoiceResponse>,
    val history: List<SettlementHistoryResponse>
)
