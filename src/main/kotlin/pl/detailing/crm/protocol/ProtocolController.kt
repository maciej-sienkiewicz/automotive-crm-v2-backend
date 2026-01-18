package pl.detailing.crm.protocol

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.protocol.infrastructure.ProtocolFieldMappingRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.protocol.rule.CreateProtocolRuleCommand
import pl.detailing.crm.protocol.rule.CreateProtocolRuleHandler
import pl.detailing.crm.protocol.rule.GetProtocolRulesHandler
import pl.detailing.crm.protocol.template.CreateProtocolTemplateCommand
import pl.detailing.crm.protocol.template.CreateProtocolTemplateHandler
import pl.detailing.crm.protocol.template.GetProtocolTemplatesHandler
import pl.detailing.crm.protocol.visitprotocol.GenerateVisitProtocolsCommand
import pl.detailing.crm.protocol.visitprotocol.GenerateVisitProtocolsHandler
import pl.detailing.crm.protocol.visitprotocol.GetVisitProtocolsHandler
import pl.detailing.crm.protocol.visitprotocol.SignVisitProtocolCommand
import pl.detailing.crm.protocol.visitprotocol.SignVisitProtocolHandler
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import java.util.*

@RestController
@RequestMapping("/api/v1")
class ProtocolController(
    private val createProtocolTemplateHandler: CreateProtocolTemplateHandler,
    private val getProtocolTemplatesHandler: GetProtocolTemplatesHandler,
    private val createProtocolRuleHandler: CreateProtocolRuleHandler,
    private val getProtocolRulesHandler: GetProtocolRulesHandler,
    private val generateVisitProtocolsHandler: GenerateVisitProtocolsHandler,
    private val signVisitProtocolHandler: SignVisitProtocolHandler,
    private val getVisitProtocolsHandler: GetVisitProtocolsHandler,
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val protocolFieldMappingRepository: ProtocolFieldMappingRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val s3StorageService: S3ProtocolStorageService,
    private val serviceRepository: ServiceRepository
) {

    // ==================== Protocol Templates ====================

    @GetMapping("/protocol-templates")
    fun getProtocolTemplates(): ResponseEntity<List<ProtocolTemplateResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getProtocolTemplatesHandler.handle(principal.studioId)

        val responses = result.templates.map { template ->
            toProtocolTemplateResponse(template, principal.studioId)
        }

        ResponseEntity.ok(responses)
    }

    @GetMapping("/protocol-templates/{id}")
    fun getProtocolTemplate(@PathVariable id: String): ResponseEntity<ProtocolTemplateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getProtocolTemplatesHandler.handleGetById(principal.studioId, id)

        ResponseEntity.ok(toProtocolTemplateResponse(result.template, principal.studioId, result.downloadUrl))
    }

    @PostMapping("/protocol-templates")
    fun createProtocolTemplate(
        @RequestBody request: CreateProtocolTemplateRequest
    ): ResponseEntity<ProtocolTemplateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create protocol templates")
        }

        val command = CreateProtocolTemplateCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            name = request.name,
            description = request.description
        )

        val result = createProtocolTemplateHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(toProtocolTemplateResponse(result.template, principal.studioId, result.uploadUrl))
    }

    @PatchMapping("/protocol-templates/{id}")
    fun updateProtocolTemplate(
        @PathVariable id: String,
        @RequestBody request: UpdateProtocolTemplateRequest
    ): ResponseEntity<ProtocolTemplateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update protocol templates")
        }

        val template = protocolTemplateRepository.findByIdAndStudioId(
            UUID.fromString(id),
            principal.studioId.value
        )?.toDomain() ?: throw NotFoundException("Protocol template not found")

        var updated = template
        request.name?.let { updated = updated.copy(name = it.trim(), updatedBy = principal.userId) }
        request.description?.let { updated = updated.copy(description = it.trim(), updatedBy = principal.userId) }
        request.isActive?.let {
            updated = if (it) updated.activate(principal.userId) else updated.deactivate(principal.userId)
        }

        protocolTemplateRepository.save(pl.detailing.crm.protocol.infrastructure.ProtocolTemplateEntity.fromDomain(updated))

        ResponseEntity.ok(toProtocolTemplateResponse(updated, principal.studioId))
    }

    @DeleteMapping("/protocol-templates/{id}")
    fun deleteProtocolTemplate(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can delete protocol templates")
        }

        val template = protocolTemplateRepository.findByIdAndStudioId(
            UUID.fromString(id),
            principal.studioId.value
        ) ?: throw NotFoundException("Protocol template not found")

        protocolTemplateRepository.delete(template)

        ResponseEntity.noContent().build()
    }

    // ==================== Protocol Rules ====================

    @GetMapping("/protocol-rules")
    fun getProtocolRules(
        @RequestParam(required = false) stage: String?
    ): ResponseEntity<List<ProtocolRuleResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val protocolStage = stage?.let { ProtocolStage.valueOf(it) }
        val result = getProtocolRulesHandler.handle(principal.studioId, protocolStage)

        val responses = result.rules.map { rule ->
            toProtocolRuleResponse(rule, principal.studioId)
        }

        ResponseEntity.ok(responses)
    }

    @PostMapping("/protocol-rules")
    fun createProtocolRule(
        @RequestBody request: CreateProtocolRuleRequest
    ): ResponseEntity<ProtocolRuleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create protocol rules")
        }

        val command = CreateProtocolRuleCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            templateId = ProtocolTemplateId.fromString(request.protocolTemplateId),
            triggerType = request.triggerType,
            stage = request.stage,
            serviceIds = request.serviceIds?.map { ServiceId.fromString(it) }?.toSet() ?: emptySet(),
            isMandatory = request.isMandatory,
            displayOrder = request.displayOrder ?: 0
        )

        val result = createProtocolRuleHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(toProtocolRuleResponse(result.rule, principal.studioId))
    }

    @DeleteMapping("/protocol-rules/{id}")
    fun deleteProtocolRule(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can delete protocol rules")
        }

        // Note: Delete is intentionally not implemented to prevent accidental data loss
        // Instead, rules should be managed through creation of new rules
        throw ForbiddenException("Deleting protocol rules is not allowed. Create new rules instead.")
    }

    // ==================== CRM Data Keys ====================

    @GetMapping("/protocol-crm-data-keys")
    fun getCrmDataKeys(): ResponseEntity<List<CrmDataKeyInfo>> {
        val keys = CrmDataKey.entries.map { key ->
            CrmDataKeyInfo(
                key = key.name,
                description = key.description
            )
        }
        return ResponseEntity.ok(keys)
    }

    // ==================== Visit Protocols ====================

    @GetMapping("/visits/{visitId}/protocols")
    fun getVisitProtocols(@PathVariable visitId: String): ResponseEntity<List<VisitProtocolResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = getVisitProtocolsHandler.handle(
            VisitId.fromString(visitId),
            principal.studioId
        )

        val responses = result.protocols.map { protocol ->
            toVisitProtocolResponse(protocol, principal.studioId)
        }

        ResponseEntity.ok(responses)
    }

    @PostMapping("/visits/{visitId}/protocols/generate")
    fun generateVisitProtocols(
        @PathVariable visitId: String,
        @RequestParam(required = false, defaultValue = "CHECK_IN") stage: String
    ): ResponseEntity<List<VisitProtocolResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GenerateVisitProtocolsCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            stage = ProtocolStage.valueOf(stage)
        )

        val result = generateVisitProtocolsHandler.handle(command)

        val responses = result.protocols.map { protocol ->
            toVisitProtocolResponse(protocol, principal.studioId)
        }

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(responses)
    }

    @PostMapping("/visits/{visitId}/protocols/{protocolId}/sign")
    fun signVisitProtocol(
        @PathVariable visitId: String,
        @PathVariable protocolId: String,
        @RequestBody request: SignProtocolRequest
    ): ResponseEntity<VisitProtocolResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = SignVisitProtocolCommand(
            visitId = VisitId.fromString(visitId),
            protocolId = VisitProtocolId.fromString(protocolId),
            studioId = principal.studioId,
            signatureUrl = request.signatureUrl,
            signedBy = request.signedBy,
            notes = request.notes
        )

        val result = signVisitProtocolHandler.handle(command)

        ResponseEntity.ok(toVisitProtocolResponse(result.protocol, principal.studioId))
    }

    // ==================== Helper Methods ====================

    private suspend fun toProtocolTemplateResponse(
        template: pl.detailing.crm.protocol.domain.ProtocolTemplate,
        studioId: StudioId,
        templateUrl: String? = null
    ): ProtocolTemplateResponse {
        val url = templateUrl ?: s3StorageService.generateDownloadUrl(template.s3Key)
        return ProtocolTemplateResponse(
            id = template.id.toString(),
            name = template.name,
            description = template.description,
            templateUrl = url,
            isActive = template.isActive,
            createdAt = template.createdAt.toString(),
            updatedAt = template.updatedAt.toString()
        )
    }

    private suspend fun toProtocolRuleResponse(
        rule: pl.detailing.crm.protocol.domain.ProtocolRule,
        studioId: StudioId
    ): ProtocolRuleResponse {
        val template = protocolTemplateRepository.findByIdAndStudioId(
            rule.templateId.value,
            studioId.value
        )?.toDomain()

        val serviceNames = rule.serviceIds.mapNotNull { serviceId ->
            serviceRepository.findByIdAndStudioId(serviceId.value, studioId.value)?.name
        }

        return ProtocolRuleResponse(
            id = rule.id.toString(),
            protocolTemplateId = rule.templateId.toString(),
            protocolTemplate = template?.let { toProtocolTemplateResponse(it, studioId) },
            triggerType = rule.triggerType.name,
            stage = rule.stage.name,
            serviceIds = rule.serviceIds.map { it.toString() },
            serviceNames = serviceNames,
            isMandatory = rule.isMandatory,
            displayOrder = rule.displayOrder,
            createdAt = rule.createdAt.toString(),
            updatedAt = rule.updatedAt.toString()
        )
    }

    private suspend fun toVisitProtocolResponse(
        protocol: pl.detailing.crm.protocol.domain.VisitProtocol,
        studioId: StudioId
    ): VisitProtocolResponse {
        val template = protocolTemplateRepository.findByIdAndStudioId(
            protocol.templateId.value,
            studioId.value
        )?.toDomain()

        val signatureUrl = protocol.signedPdfS3Key?.let { s3Key ->
            s3StorageService.generateDownloadUrl(s3Key)
        }

        return VisitProtocolResponse(
            id = protocol.id.toString(),
            visitId = protocol.visitId.toString(),
            protocolTemplateId = protocol.templateId.toString(),
            protocolTemplate = template?.let { toProtocolTemplateResponse(it, studioId) },
            stage = protocol.stage.name,
            isMandatory = protocol.isMandatory,
            isSigned = protocol.status == VisitProtocolStatus.SIGNED,
            signedAt = protocol.signedAt?.toString(),
            signedBy = protocol.signedBy,
            signatureUrl = signatureUrl,
            notes = protocol.notes,
            createdAt = protocol.createdAt.toString(),
            updatedAt = protocol.updatedAt.toString()
        )
    }
}
