package pl.detailing.crm.invoicing.issue

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.*
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceEntity
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class IssueInvoiceCommand(
    val studioId: StudioId,
    val buyerName: String,
    val buyerNip: String?,
    val buyerEmail: String?,
    val buyerStreet: String?,
    val buyerCity: String?,
    val buyerPostCode: String?,
    val items: List<InvoiceItemCommand>,
    val paymentMethod: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val currency: String = "PLN",
    val notes: String?
)

data class InvoiceItemCommand(
    val name: String,
    val quantity: Double,
    val unit: String,
    val unitNetPriceInCents: Long,
    val vatRate: Int
)

/**
 * Issues a new invoice via the studio's configured external provider.
 *
 * Flow:
 * 1. Loads API credentials for the studio's provider.
 * 2. Validates that required fields are present (e.g. at least one item).
 * 3. Calls the provider adapter to create the invoice.
 * 4. Persists a local record to the [ExternalInvoiceEntity] table.
 * 5. Returns the created [ExternalInvoice].
 */
@Service
class IssueInvoiceHandler(
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val invoiceRepository: ExternalInvoiceRepository,
    private val providerRegistry: InvoiceProviderRegistry
) {

    @Transactional
    fun handle(command: IssueInvoiceCommand): ExternalInvoice {
        val credentials = credentialsRepository.findByStudioId(command.studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()

        validateCommand(command)

        val provider = providerRegistry.getProvider(credentials.provider)

        val request = IssueInvoiceRequest(
            buyerName      = command.buyerName,
            buyerNip       = command.buyerNip,
            buyerEmail     = command.buyerEmail,
            buyerStreet    = command.buyerStreet,
            buyerCity      = command.buyerCity,
            buyerPostCode  = command.buyerPostCode,
            items          = command.items.map { item ->
                InvoiceItem(
                    name                = item.name,
                    quantity            = item.quantity,
                    unit                = item.unit,
                    unitNetPriceInCents = item.unitNetPriceInCents,
                    vatRate             = item.vatRate
                )
            },
            paymentMethod  = command.paymentMethod,
            issueDate      = command.issueDate,
            dueDate        = command.dueDate,
            currency       = command.currency,
            notes          = command.notes
        )

        val snapshot = provider.issueInvoice(credentials.apiKey, request)

        val entity = ExternalInvoiceEntity(
            id                   = UUID.randomUUID(),
            studioId             = command.studioId.value,
            provider             = credentials.provider,
            externalId           = snapshot.externalId,
            externalNumber       = snapshot.externalNumber,
            status               = snapshot.status,
            isCorrection         = snapshot.isCorrection,
            hasCorrection        = snapshot.hasCorrection,
            correctionExternalId = snapshot.correctionExternalId,
            grossAmount          = snapshot.grossAmountInCents,
            netAmount            = snapshot.netAmountInCents,
            vatAmount            = snapshot.vatAmountInCents,
            currency             = snapshot.currency,
            issueDate            = snapshot.issueDate,
            dueDate              = snapshot.dueDate,
            buyerName            = snapshot.buyerName,
            buyerNip             = snapshot.buyerNip,
            description          = command.notes,
            syncedAt             = Instant.now(),
            createdAt            = Instant.now(),
            updatedAt            = Instant.now()
        )

        val saved = invoiceRepository.save(entity)
        return saved.toDomain(provider.getInvoicePortalUrl(snapshot.externalId))
    }

    private fun validateCommand(command: IssueInvoiceCommand) {
        val errors = mutableListOf<String>()

        if (command.buyerName.isBlank()) {
            errors += "Nazwa nabywcy jest wymagana"
        }
        if (command.items.isEmpty()) {
            errors += "Faktura musi zawierać co najmniej jedną pozycję"
        }
        command.items.forEachIndexed { index, item ->
            if (item.name.isBlank()) errors += "Pozycja ${index + 1}: nazwa jest wymagana"
            if (item.quantity <= 0) errors += "Pozycja ${index + 1}: ilość musi być większa od 0"
            if (item.unitNetPriceInCents < 0) errors += "Pozycja ${index + 1}: cena nie może być ujemna"
        }
        if (command.paymentMethod.uppercase() == "TRANSFER" && command.dueDate == null) {
            errors += "Termin płatności jest wymagany dla płatności przelewem"
        }
        if (command.dueDate != null && command.dueDate.isBefore(command.issueDate)) {
            errors += "Termin płatności nie może być wcześniejszy niż data wystawienia"
        }

        if (errors.isNotEmpty()) {
            throw InvoicingValidationException(
                "Błąd walidacji faktury: ${errors.joinToString("; ")}",
                errors
            )
        }
    }
}
