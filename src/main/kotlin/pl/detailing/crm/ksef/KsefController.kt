package pl.detailing.crm.ksef

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateType
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.ksef.auth.KsefSessionCache
import pl.detailing.crm.ksef.credentials.KsefCredentialsEntity
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesCommand
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler
import pl.detailing.crm.ksef.list.ListKsefInvoicesCommand
import pl.detailing.crm.ksef.list.ListKsefInvoicesHandler
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/v1/ksef")
class KsefController(
    private val credentialsRepository: KsefCredentialsRepository,
    private val sessionCache: KsefSessionCache,
    private val ksefAuthService: KsefAuthService,
    private val fetchInvoicesHandler: FetchKsefInvoicesHandler,
    private val listInvoicesHandler: ListKsefInvoicesHandler
) {

    // ─────────────────────────────────────────────────────────────────────
    // Credentials management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Save (create or replace) KSeF credentials for the current studio.
     * Only OWNER role can manage KSeF credentials.
     *
     * POST /api/v1/ksef/credentials
     */
    @PostMapping("/credentials")
    @Transactional
    fun saveCredentials(@RequestBody request: SaveKsefCredentialsRequest): ResponseEntity<KsefCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can configure KSeF credentials")
        }

        val studioId = principal.studioId.value

        // Remove existing credentials and cached session before saving new ones
        credentialsRepository.deleteByStudioId(studioId)
        sessionCache.invalidate(principal.studioId)

        val entity = credentialsRepository.save(
            KsefCredentialsEntity(
                studioId = studioId,
                nip = request.nip.trim(),
                ksefToken = request.ksefToken.trim()
            )
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(entity.toResponse())
    }

    /**
     * Get KSeF credentials summary for the current studio (token is masked).
     *
     * GET /api/v1/ksef/credentials
     */
    @GetMapping("/credentials")
    fun getCredentials(): ResponseEntity<KsefCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can view KSeF credentials")
        }

        val entity = credentialsRepository.findByStudioId(principal.studioId.value)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(entity.toResponse())
    }

    /**
     * Delete KSeF credentials for the current studio.
     *
     * DELETE /api/v1/ksef/credentials
     */
    @DeleteMapping("/credentials")
    @Transactional
    fun deleteCredentials(): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can delete KSeF credentials")
        }

        credentialsRepository.deleteByStudioId(principal.studioId.value)
        sessionCache.invalidate(principal.studioId)

        return ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Session management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Authenticate with KSeF and open a session. Useful to verify credentials
     * or to pre-warm the session cache.
     *
     * POST /api/v1/ksef/session
     */
    @PostMapping("/session")
    fun startSession(): ResponseEntity<KsefSessionResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER or MANAGER can manage KSeF sessions")
        }

        val session = ksefAuthService.authenticate(principal.studioId)

        return ResponseEntity.ok(
            KsefSessionResponse(
                authenticated = true,
                accessTokenValidUntil = session.accessTokenValidUntil
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invoice fetching
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Fetch invoice metadata from KSeF for the given date range and persist it locally.
     * Returns the count of new invoices saved and any already-present ones skipped.
     *
     * POST /api/v1/ksef/invoices/fetch
     */
    @PostMapping("/invoices/fetch")
    fun fetchInvoices(@RequestBody request: FetchKsefInvoicesRequest): ResponseEntity<FetchKsefInvoicesResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER or MANAGER can fetch KSeF invoices")
        }

        val dateType = when (request.dateType?.uppercase()) {
            "ISSUE", "ISSUEDATE" -> InvoiceQueryDateType.ISSUE
            "PERMANENTSTORAGE", "PERMANENTSTORAGEDATE" -> InvoiceQueryDateType.PERMANENTSTORAGE
            else -> InvoiceQueryDateType.INVOICING  // default: invoicing date
        }

        val subjectType = when (request.subjectType?.uppercase()) {
            "SUBJECT2" -> InvoiceQuerySubjectType.SUBJECT2
            "SUBJECT3" -> InvoiceQuerySubjectType.SUBJECT3
            "SUBJECTAUTHORIZED" -> InvoiceQuerySubjectType.SUBJECTAUTHORIZED
            else -> InvoiceQuerySubjectType.SUBJECT1  // default: seller perspective
        }

        val result = fetchInvoicesHandler.handle(
            FetchKsefInvoicesCommand(
                studioId = principal.studioId,
                dateFrom = request.dateFrom,
                dateTo = request.dateTo,
                dateType = dateType,
                subjectType = subjectType,
                pageSize = request.pageSize ?: 50
            )
        )

        return ResponseEntity.ok(
            FetchKsefInvoicesResponse(
                fetched = result.fetched,
                skipped = result.skipped,
                total = result.fetched + result.skipped
            )
        )
    }

    /**
     * List locally stored KSeF invoices for the current studio.
     *
     * GET /api/v1/ksef/invoices?page=1&size=20
     */
    @GetMapping("/invoices")
    fun listInvoices(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<KsefInvoiceListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listInvoicesHandler.handle(
            ListKsefInvoicesCommand(
                studioId = principal.studioId,
                page = maxOf(1, page),
                pageSize = size.coerceIn(1, 100)
            )
        )

        return ResponseEntity.ok(
            KsefInvoiceListResponse(
                invoices = result.invoices.map { inv ->
                    KsefInvoiceResponse(
                        id = inv.id.toString(),
                        ksefNumber = inv.ksefNumber,
                        invoiceNumber = inv.invoiceNumber,
                        invoicingDate = inv.invoicingDate,
                        issueDate = inv.issueDate?.toString(),
                        sellerNip = inv.sellerNip,
                        buyerNip = inv.buyerNip,
                        netAmount = inv.netAmount,
                        grossAmount = inv.grossAmount,
                        vatAmount = inv.vatAmount,
                        currency = inv.currency,
                        invoiceType = inv.invoiceType,
                        fetchedAt = inv.fetchedAt
                    )
                },
                total = result.total,
                page = result.page,
                pageSize = result.pageSize
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun KsefCredentialsEntity.toResponse() = KsefCredentialsResponse(
        nip = nip,
        tokenMasked = maskToken(ksefToken),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun maskToken(token: String): String {
        return if (token.length <= 8) "****" else token.take(4) + "****" + token.takeLast(4)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request / Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class SaveKsefCredentialsRequest(
    val nip: String,
    val ksefToken: String
)

data class KsefCredentialsResponse(
    val nip: String,
    val tokenMasked: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class KsefSessionResponse(
    val authenticated: Boolean,
    val accessTokenValidUntil: OffsetDateTime
)

data class FetchKsefInvoicesRequest(
    val dateFrom: OffsetDateTime,
    val dateTo: OffsetDateTime,
    val dateType: String?,       // InvoicingDate | AcquisitionDate | IssueDate
    val subjectType: String?,    // Subject1 | Subject2 | Subject3
    val pageSize: Int?
)

data class FetchKsefInvoicesResponse(
    val fetched: Int,
    val skipped: Int,
    val total: Int
)

data class KsefInvoiceResponse(
    val id: String,
    val ksefNumber: String,
    val invoiceNumber: String?,
    val invoicingDate: OffsetDateTime?,
    val issueDate: String?,
    val sellerNip: String?,
    val buyerNip: String?,
    val netAmount: Double?,
    val grossAmount: Double?,
    val vatAmount: Double?,
    val currency: String?,
    val invoiceType: String?,
    val fetchedAt: Instant
)

data class KsefInvoiceListResponse(
    val invoices: List<KsefInvoiceResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)
