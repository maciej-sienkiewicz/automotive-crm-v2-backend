package pl.detailing.crm.invoicing

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.finance.document.ImportProviderInvoicesHandler
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsEntity
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

@RestController
@RequestMapping("/api/v1/invoicing")
class InvoicingController(
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val importProviderInvoicesHandler: ImportProviderInvoicesHandler,
    private val providerRegistry: InvoiceProviderRegistry
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Credentials management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configure the invoicing provider and API key for the current studio.
     *
     * Verifies the API key against the provider's API before saving.
     * Returns 422 if the key is rejected by the provider – credentials are NOT saved in that case.
     * Replaces any previously configured provider on success.
     * After saving, triggers an asynchronous import of all existing provider invoices.
     *
     * POST /api/v1/invoicing/credentials
     */
    @PostMapping("/credentials")
    @Transactional
    fun saveCredentials(
        @RequestBody request: SaveInvoicingCredentialsRequest
    ): ResponseEntity<InvoicingCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może konfigurować integrację z dostawcą faktur")
        }

        val provider = parseProvider(request.provider)
        val adapter  = providerRegistry.getProvider(provider)
        val apiKey   = request.apiKey.trim()

        val verification = adapter.verifyCredentials(apiKey)
        if (!verification.valid) {
            throw InvoicingValidationException(
                verification.errorMessage
                    ?: "Podany klucz API jest nieprawidłowy. Sprawdź konfigurację w panelu dostawcy."
            )
        }

        credentialsRepository.deleteByStudioId(principal.studioId.value)

        val entity = credentialsRepository.save(
            InvoicingCredentialsEntity(
                studioId = principal.studioId.value,
                provider = provider,
                apiKey   = apiKey
            )
        )

        try {
            importProviderInvoicesHandler.handleWithCredentials(principal.studioId, provider, apiKey)
        } catch (ex: Exception) {
            // Import failure must not block the credential save — the user can trigger it manually later.
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(entity.toResponse())
    }

    /**
     * Retrieve current invoicing provider configuration (API key masked).
     * GET /api/v1/invoicing/credentials
     */
    @GetMapping("/credentials")
    fun getCredentials(): ResponseEntity<InvoicingCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może wyświetlić konfigurację integracji")
        }

        val entity = credentialsRepository.findByStudioId(principal.studioId.value)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(entity.toResponse())
    }

    /**
     * Remove invoicing provider configuration.
     * DELETE /api/v1/invoicing/credentials
     */
    @DeleteMapping("/credentials")
    @Transactional
    fun deleteCredentials(): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może usunąć konfigurację integracji")
        }

        credentialsRepository.deleteByStudioId(principal.studioId.value)
        return ResponseEntity.noContent().build()
    }

    /**
     * Returns a list of all supported invoicing providers.
     * GET /api/v1/invoicing/providers
     */
    @GetMapping("/providers")
    fun listProviders(): ResponseEntity<List<InvoiceProviderInfoResponse>> {
        val providers = InvoiceProviderType.entries.map { provider ->
            InvoiceProviderInfoResponse(
                type        = provider.name,
                displayName = provider.displayName,
                supported   = runCatching { providerRegistry.getProvider(provider) }.isSuccess
            )
        }
        return ResponseEntity.ok(providers)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseProvider(value: String): InvoiceProviderType {
        return runCatching { InvoiceProviderType.valueOf(value.uppercase()) }.getOrElse {
            throw ValidationException(
                "Nieznany dostawca faktur: '$value'. Dostępne: ${InvoiceProviderType.entries.joinToString { it.name }}"
            )
        }
    }

    private fun InvoicingCredentialsEntity.toResponse() = InvoicingCredentialsResponse(
        provider      = provider.name,
        providerLabel = provider.displayName,
        apiKeyMasked  = maskApiKey(apiKey),
        verified      = true,
        createdAt     = createdAt,
        updatedAt     = updatedAt
    )

    private fun maskApiKey(key: String): String =
        if (key.length <= 8) "****" else key.take(4) + "****" + key.takeLast(4)
}

// ─────────────────────────────────────────────────────────────────────────────
// Request DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class SaveInvoicingCredentialsRequest(
    /** Provider type: INFAKT | WFIRMA | IFIRMA | FAKTUROWNIA */
    val provider: String,
    val apiKey: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class InvoicingCredentialsResponse(
    val provider: String,
    val providerLabel: String,
    val apiKeyMasked: String,
    val verified: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class InvoiceProviderInfoResponse(
    val type: String,
    val displayName: String,
    /** True if an adapter has been implemented for this provider. */
    val supported: Boolean
)
