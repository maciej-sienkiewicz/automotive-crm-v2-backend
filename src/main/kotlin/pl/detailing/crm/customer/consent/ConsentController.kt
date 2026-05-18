package pl.detailing.crm.customer.consent

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.customer.consent.addversion.AddConsentVersionCommand
import pl.detailing.crm.customer.consent.addversion.AddConsentVersionHandler
import pl.detailing.crm.customer.consent.create.CreateConsentCommand
import pl.detailing.crm.customer.consent.create.CreateConsentHandler
import pl.detailing.crm.customer.consent.create.CreateConsentResult
import pl.detailing.crm.customer.consent.create.TemplateVersionInfo
import pl.detailing.crm.customer.consent.get.ConsentResponse
import pl.detailing.crm.customer.consent.get.ConsentVersionResponse
import pl.detailing.crm.customer.consent.get.GetConsentsHandler
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.update.UpdateConsentCommand
import pl.detailing.crm.customer.consent.update.UpdateConsentHandler
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.*

/**
 * Admin API for managing customer consents.
 *
 * Base URL: /api/v1/consents
 */
@RestController
@RequestMapping("/api/v1/consents")
class ConsentController(
    private val createConsentHandler: CreateConsentHandler,
    private val addConsentVersionHandler: AddConsentVersionHandler,
    private val getConsentsHandler: GetConsentsHandler,
    private val updateConsentHandler: UpdateConsentHandler,
    private val consentDefinitionRepository: ConsentDefinitionRepository
) {

    @GetMapping
    fun listConsents(): ResponseEntity<List<ConsentResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireOwnerOrManager(principal)
        ResponseEntity.ok(getConsentsHandler.handleList(principal.studioId))
    }

    @GetMapping("/{id}")
    fun getConsent(@PathVariable id: UUID): ResponseEntity<ConsentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireOwnerOrManager(principal)
        ResponseEntity.ok(getConsentsHandler.handleGet(principal.studioId, ConsentDefinitionId(id)))
    }

    /**
     * Create a new consent definition and its first PDF version.
     * Response includes a presigned S3 upload URL to upload the PDF directly from the browser.
     */
    @PostMapping
    fun createConsent(
        @RequestBody request: CreateConsentRequest
    ): ResponseEntity<ConsentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireOwnerOrManager(principal)

        val result = createConsentHandler.handle(
            CreateConsentCommand(
                studioId = principal.studioId,
                createdBy = principal.userId,
                name = request.name,
                description = request.description,
                stage = request.stage,
                marketingChannels = request.marketingChannels
                    .mapNotNull { runCatching { MarketingChannel.valueOf(it) }.getOrNull() }
                    .toSet(),
                displayOrder = request.displayOrder ?: 0
            )
        )

        ResponseEntity.status(HttpStatus.CREATED).body(toConsentResponse(result))
    }

    /**
     * Update consent configuration (name, stage, marketing channels, display order).
     * Does not affect existing customer signatures.
     */
    @PatchMapping("/{id}")
    fun updateConsent(
        @PathVariable id: UUID,
        @RequestBody request: UpdateConsentRequest
    ): ResponseEntity<ConsentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireOwnerOrManager(principal)

        updateConsentHandler.handle(
            UpdateConsentCommand(
                studioId = principal.studioId,
                updatedBy = principal.userId,
                definitionId = ConsentDefinitionId(id),
                name = request.name,
                description = request.description,
                stage = request.stage,
                marketingChannels = request.marketingChannels?.mapNotNull {
                    runCatching { MarketingChannel.valueOf(it) }.getOrNull()
                }?.toSet(),
                displayOrder = request.displayOrder
            )
        )

        ResponseEntity.ok(getConsentsHandler.handleGet(principal.studioId, ConsentDefinitionId(id)))
    }

    /**
     * Deactivate a consent definition.
     * Existing customer signatures are preserved in the audit trail.
     */
    @DeleteMapping("/{id}")
    fun deactivateConsent(@PathVariable id: UUID): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireOwnerOrManager(principal)

        val entity = consentDefinitionRepository.findByIdAndStudioId(id, principal.studioId.value)
            ?: throw NotFoundException("Zgoda nie została znaleziona")

        entity.isActive = false
        entity.updatedBy = principal.userId.value
        entity.updatedAt = Instant.now()
        consentDefinitionRepository.save(entity)

        ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/versions")
    fun listVersions(@PathVariable id: UUID): ResponseEntity<ConsentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireOwnerOrManager(principal)
        ResponseEntity.ok(getConsentsHandler.handleGet(principal.studioId, ConsentDefinitionId(id)))
    }

    /**
     * Publish a new PDF version for an existing consent.
     * Set requiresResign=true to force customers who already signed to sign again.
     */
    @PostMapping("/{id}/versions")
    fun addVersion(
        @PathVariable id: UUID,
        @RequestBody request: AddVersionRequest
    ): ResponseEntity<ConsentVersionResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        requireOwnerOrManager(principal)

        val result = addConsentVersionHandler.handle(
            AddConsentVersionCommand(
                studioId = principal.studioId,
                createdBy = principal.userId,
                definitionId = ConsentDefinitionId(id),
                requiresResign = request.requiresResign ?: false,
                setAsActive = request.setAsActive ?: true
            )
        )

        ResponseEntity.status(HttpStatus.CREATED).body(
            ConsentVersionResponse(
                versionId = result.versionId.value,
                version = result.version,
                isActive = result.isActive,
                requiresResign = result.requiresResign,
                pdfUrl = result.pdfUploadUrl,
                createdAt = Instant.now()
            )
        )
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun requireOwnerOrManager(principal: pl.detailing.crm.auth.UserPrincipal) {
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą zarządzać zgodami")
        }
    }

    private fun toConsentResponse(result: CreateConsentResult): ConsentResponse =
        ConsentResponse(
            id = result.definitionId.value,
            name = result.name,
            description = result.description,
            stage = result.stage,
            marketingChannels = result.marketingChannels.map { it.name }.toSet(),
            displayOrder = result.displayOrder,
            isActive = true,
            currentVersion = ConsentVersionResponse(
                versionId = result.currentVersion.versionId.value,
                version = result.currentVersion.version,
                isActive = result.currentVersion.isActive,
                requiresResign = result.currentVersion.requiresResign,
                pdfUrl = result.currentVersion.pdfUploadUrl,
                createdAt = Instant.now()
            ),
            versions = listOf(
                ConsentVersionResponse(
                    versionId = result.currentVersion.versionId.value,
                    version = result.currentVersion.version,
                    isActive = result.currentVersion.isActive,
                    requiresResign = result.currentVersion.requiresResign,
                    pdfUrl = result.currentVersion.pdfUploadUrl,
                    createdAt = Instant.now()
                )
            ),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
}

// ─── Request DTOs ─────────────────────────────────────────────────────────────

data class CreateConsentRequest(
    val name: String,
    val description: String? = null,
    val stage: ProtocolStage,
    val marketingChannels: List<String> = emptyList(),  // "EMAIL", "SMS"
    val displayOrder: Int? = 0
)

data class UpdateConsentRequest(
    val name: String? = null,
    val description: String? = null,
    val stage: ProtocolStage? = null,
    val marketingChannels: List<String>? = null,
    val displayOrder: Int? = null
)

data class AddVersionRequest(
    val requiresResign: Boolean? = false,
    val setAsActive: Boolean? = true
)
