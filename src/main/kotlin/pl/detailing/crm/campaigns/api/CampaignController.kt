package pl.detailing.crm.campaigns.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.campaigns.application.*
import pl.detailing.crm.campaigns.domain.*
import pl.detailing.crm.campaigns.infrastructure.AudienceEstimate
import pl.detailing.crm.campaigns.infrastructure.AudienceRow
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.OutboundMessageCategory
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class AudienceCriteriaDto(
    val visitCountMin: Int? = null,
    val visitCountMax: Int? = null,
    val lastVisitOlderThanDays: Int? = null,
    val lastVisitNewerThanDays: Int? = null,
    val revenueTotalGrossMin: Long? = null,
    val revenueTotalGrossMax: Long? = null,
    val servicesUsedAnyOf: List<UUID> = emptyList(),
    val servicesUsedNoneOf: List<UUID> = emptyList(),
    val serviceLastUsedOlderThanDays: Int? = null,
    val vehicleBrands: List<VehicleBrandFilterDto> = emptyList(),
    val vehicleYearMin: Int? = null,
    val vehicleYearMax: Int? = null,
    val customerType: CustomerTypeFilter = CustomerTypeFilter.ALL,
    val customerCreatedAfter: Instant? = null,
    val includeCustomerIds: List<UUID> = emptyList(),
    val excludeCustomerIds: List<UUID> = emptyList()
) {
    fun toDomain() = AudienceCriteria(
        visitCountMin = visitCountMin,
        visitCountMax = visitCountMax,
        lastVisitOlderThanDays = lastVisitOlderThanDays,
        lastVisitNewerThanDays = lastVisitNewerThanDays,
        revenueTotalGrossMin = revenueTotalGrossMin,
        revenueTotalGrossMax = revenueTotalGrossMax,
        servicesUsedAnyOf = servicesUsedAnyOf,
        servicesUsedNoneOf = servicesUsedNoneOf,
        serviceLastUsedOlderThanDays = serviceLastUsedOlderThanDays,
        vehicleBrands = vehicleBrands.map { VehicleBrandFilter(it.brand, it.model) },
        vehicleYearMin = vehicleYearMin,
        vehicleYearMax = vehicleYearMax,
        customerType = customerType,
        customerCreatedAfter = customerCreatedAfter,
        includeCustomerIds = includeCustomerIds,
        excludeCustomerIds = excludeCustomerIds
    )

    companion object {
        fun fromDomain(a: AudienceCriteria) = AudienceCriteriaDto(
            visitCountMin = a.visitCountMin,
            visitCountMax = a.visitCountMax,
            lastVisitOlderThanDays = a.lastVisitOlderThanDays,
            lastVisitNewerThanDays = a.lastVisitNewerThanDays,
            revenueTotalGrossMin = a.revenueTotalGrossMin,
            revenueTotalGrossMax = a.revenueTotalGrossMax,
            servicesUsedAnyOf = a.servicesUsedAnyOf,
            servicesUsedNoneOf = a.servicesUsedNoneOf,
            serviceLastUsedOlderThanDays = a.serviceLastUsedOlderThanDays,
            vehicleBrands = a.vehicleBrands.map { VehicleBrandFilterDto(it.brand, it.model) },
            vehicleYearMin = a.vehicleYearMin,
            vehicleYearMax = a.vehicleYearMax,
            customerType = a.customerType,
            customerCreatedAfter = a.customerCreatedAfter,
            includeCustomerIds = a.includeCustomerIds,
            excludeCustomerIds = a.excludeCustomerIds
        )
    }
}

data class VehicleBrandFilterDto(val brand: String, val model: String? = null)

data class TriggerConfigDto(
    val serviceIds: List<UUID>,
    val afterDays: Int,
    val sendTime: String = "10:00",
    val onlyIfNoVisitSince: Boolean = true
) {
    fun toDomain() = TriggerConfig(serviceIds, afterDays, sendTime, onlyIfNoVisitSince)

    companion object {
        fun fromDomain(t: TriggerConfig) = TriggerConfigDto(t.serviceIds, t.afterDays, t.sendTime, t.onlyIfNoVisitSince)
    }
}

data class CampaignRequest(
    val name: String,
    val kind: CampaignKind,
    val channel: CampaignChannel,
    val audience: AudienceCriteriaDto = AudienceCriteriaDto(),
    val smsTemplate: String? = null,
    val emailSubject: String? = null,
    val emailBody: String? = null,
    val scheduledAt: Instant? = null,
    val trigger: TriggerConfigDto? = null
) {
    fun toCommand() = CreateCampaignCommand(
        name = name,
        kind = kind,
        channel = channel,
        audience = audience.toDomain(),
        smsTemplate = smsTemplate,
        emailSubject = emailSubject,
        emailBody = emailBody,
        scheduledAt = scheduledAt,
        trigger = trigger?.toDomain()
    )
}

data class CampaignDto(
    val id: UUID,
    val name: String,
    val kind: CampaignKind,
    val channel: CampaignChannel,
    val status: CampaignStatus,
    val audience: AudienceCriteriaDto,
    val smsTemplate: String?,
    val emailSubject: String?,
    val emailBody: String?,
    val scheduledAt: Instant?,
    val trigger: TriggerConfigDto?,
    val recipientsTotal: Int,
    val recipientsSent: Int,
    val recipientsFailed: Int,
    val recipientsSkipped: Int,
    val creditsSpent: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?
) {
    companion object {
        fun fromDomain(c: Campaign) = CampaignDto(
            id = c.id,
            name = c.name,
            kind = c.kind,
            channel = c.channel,
            status = c.status,
            audience = AudienceCriteriaDto.fromDomain(c.audience),
            smsTemplate = c.smsTemplate,
            emailSubject = c.emailSubject,
            emailBody = c.emailBody,
            scheduledAt = c.scheduledAt,
            trigger = c.trigger?.let { TriggerConfigDto.fromDomain(it) },
            recipientsTotal = c.recipientsTotal,
            recipientsSent = c.recipientsSent,
            recipientsFailed = c.recipientsFailed,
            recipientsSkipped = c.recipientsSkipped,
            creditsSpent = c.creditsSpent,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
            startedAt = c.startedAt,
            completedAt = c.completedAt
        )
    }
}

data class CampaignStatsDto(
    val active: Long,
    val scheduled: Long,
    val completedTotal: Long,
    val completedLast30Days: Long,
    val messagesSentLast30Days: Long,
    val smsCreditsAvailable: Int
)

data class AudienceEstimateRequest(
    val audience: AudienceCriteriaDto,
    val channel: RecipientChannel = RecipientChannel.SMS,
    val smsTemplate: String? = null
)

data class AudienceCustomerDto(
    val customerId: UUID,
    val firstName: String?,
    val lastName: String?,
    val phone: String?,
    val email: String?,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val lastVisitDate: Instant?,
    val lastServiceName: String?,
    val eligibility: AudienceRow.Eligibility
) {
    companion object {
        fun fromRow(r: AudienceRow) = AudienceCustomerDto(
            customerId = r.customerId,
            firstName = r.firstName,
            lastName = r.lastName,
            phone = r.phone,
            email = r.email,
            vehicleBrand = r.vehicleBrand,
            vehicleModel = r.vehicleModel,
            lastVisitDate = r.lastVisitDate,
            lastServiceName = r.lastServiceName,
            eligibility = r.eligibility
        )
    }
}

data class AudienceEstimateDto(
    val matched: Int,
    val optedOut: Int,
    val noConsent: Int,
    val noAddress: Int,
    val frequencyCapped: Int,
    val eligible: Int,
    val estimatedSmsSegments: Int?,
    val estimatedCredits: Int?,
    val sample: List<AudienceCustomerDto>
) {
    companion object {
        fun from(e: AudienceEstimate, smsTemplate: String?): AudienceEstimateDto {
            val segments = smsTemplate?.takeIf { it.isNotBlank() }?.let { SmsSegmentCalculator.segments(it) }
            return AudienceEstimateDto(
                matched = e.matched,
                optedOut = e.optedOut,
                noConsent = e.noConsent,
                noAddress = e.noAddress,
                frequencyCapped = e.frequencyCapped,
                eligible = e.eligible,
                estimatedSmsSegments = segments,
                estimatedCredits = segments?.let { it * e.eligible },
                sample = e.sample.map { AudienceCustomerDto.fromRow(it) }
            )
        }
    }
}

data class CampaignRecipientDto(
    val id: UUID,
    val customerId: UUID,
    val channel: RecipientChannel,
    val address: String,
    val status: RecipientStatus,
    val errorMessage: String?,
    val scheduledFor: Instant,
    val sentAt: Instant?
) {
    companion object {
        fun fromDomain(r: CampaignRecipient) = CampaignRecipientDto(
            id = r.id,
            customerId = r.customerId,
            channel = r.channel,
            address = r.address,
            status = r.status,
            errorMessage = r.errorMessage,
            scheduledFor = r.scheduledFor,
            sentAt = r.sentAt
        )
    }
}

data class ScheduleRequest(val scheduledAt: Instant? = null)

data class TestSendRequest(
    val channel: RecipientChannel,
    val address: String,
    val smsTemplate: String? = null,
    val emailSubject: String? = null,
    val emailBody: String? = null
)

data class CampaignSettingsDto(
    val quietHoursStart: String,
    val quietHoursEnd: String,
    val frequencyCapDays: Int,
    val smsFooter: String?,
    val emailFooter: String?
) {
    companion object {
        fun fromDomain(s: CampaignSettings) = CampaignSettingsDto(
            quietHoursStart = s.quietHoursStart.toString(),
            quietHoursEnd = s.quietHoursEnd.toString(),
            frequencyCapDays = s.frequencyCapDays,
            smsFooter = s.smsFooter,
            emailFooter = s.emailFooter
        )
    }
}

// ── Controller ───────────────────────────────────────────────────────────────

/**
 * REST surface of the campaigns module (docs/campaigns-module-design.md §8).
 */
@RequiresCapability(CapabilityKey.COMM_SEND_CAMPAIGN)
@RestController
@RequestMapping("/api/v1/campaigns")
@RequiresPermission(Permission.COMMUNICATION_SEND)
class CampaignController(
    private val service: CampaignService,
    private val gateway: OutboundCommunicationGateway,
    private val emailProvider: EmailProvider
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: List<CampaignStatus>?,
        @RequestParam(required = false) kind: CampaignKind?
    ): ResponseEntity<List<CampaignDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            service.list(principal.studioId, status, kind).map { CampaignDto.fromDomain(it) }
        )
    }

    @GetMapping("/stats")
    fun stats(): ResponseEntity<CampaignStatsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val s = service.stats(principal.studioId)
        return ResponseEntity.ok(
            CampaignStatsDto(
                active = s.active,
                scheduled = s.scheduled,
                completedTotal = s.completedTotal,
                completedLast30Days = s.completedLast30Days,
                messagesSentLast30Days = s.messagesSentLast30Days,
                smsCreditsAvailable = s.smsCreditsAvailable
            )
        )
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignDto.fromDomain(service.get(id, principal.studioId)))
    }

    @PostMapping
    fun create(@RequestBody request: CampaignRequest): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val created = service.create(principal.studioId, principal.userId, request.toCommand())
        return ResponseEntity.ok(CampaignDto.fromDomain(created))
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: CampaignRequest): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val updated = service.update(id, principal.studioId, principal.userId, request.toCommand())
        return ResponseEntity.ok(CampaignDto.fromDomain(updated))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        service.delete(id, principal.studioId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/schedule")
    fun schedule(@PathVariable id: UUID, @RequestBody request: ScheduleRequest): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            CampaignDto.fromDomain(service.schedule(id, principal.studioId, principal.userId, request.scheduledAt))
        )
    }

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignDto.fromDomain(service.cancel(id, principal.studioId, principal.userId)))
    }

    @PostMapping("/{id}/stop")
    fun stop(@PathVariable id: UUID): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignDto.fromDomain(service.stopSending(id, principal.studioId, principal.userId)))
    }

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: UUID): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignDto.fromDomain(service.activate(id, principal.studioId, principal.userId)))
    }

    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: UUID): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignDto.fromDomain(service.pause(id, principal.studioId, principal.userId)))
    }

    @PostMapping("/{id}/archive")
    fun archive(@PathVariable id: UUID): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignDto.fromDomain(service.archive(id, principal.studioId, principal.userId)))
    }

    @PostMapping("/{id}/duplicate")
    fun duplicate(@PathVariable id: UUID): ResponseEntity<CampaignDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignDto.fromDomain(service.duplicate(id, principal.studioId, principal.userId)))
    }

    @GetMapping("/{id}/recipients")
    fun recipients(
        @PathVariable id: UUID,
        @RequestParam(required = false) status: RecipientStatus?
    ): ResponseEntity<List<CampaignRecipientDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            service.recipientsOf(id, principal.studioId, status).map { CampaignRecipientDto.fromDomain(it) }
        )
    }

    @PostMapping("/{id}/recipients/{recipientId}/retry")
    fun retryRecipient(@PathVariable id: UUID, @PathVariable recipientId: UUID): ResponseEntity<CampaignRecipientDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            CampaignRecipientDto.fromDomain(service.retryRecipient(id, recipientId, principal.studioId))
        )
    }

    @PostMapping("/{id}/retry-failed")
    fun retryAllFailed(@PathVariable id: UUID): ResponseEntity<Map<String, Int>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val retried = service.retryAllFailed(id, principal.studioId)
        return ResponseEntity.ok(mapOf("retried" to retried))
    }

    @PostMapping("/audience/estimate")
    fun estimateAudience(@RequestBody request: AudienceEstimateRequest): ResponseEntity<AudienceEstimateDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val estimate = service.estimateAudience(principal.studioId, request.audience.toDomain(), request.channel)
        return ResponseEntity.ok(AudienceEstimateDto.from(estimate, request.smsTemplate))
    }

    @PostMapping("/test-send")
    fun testSend(@RequestBody request: TestSendRequest): ResponseEntity<Map<String, Any?>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val result: Pair<Boolean, String?> = when (request.channel) {
            RecipientChannel.SMS -> {
                val body = request.smsTemplate
                    ?: throw ValidationException("Treść SMS nie może być pusta")
                val r = gateway.sendTransactionalSms(
                    principal.studioId.value, request.address, body,
                    category = OutboundMessageCategory.CAMPAIGN
                )
                r.success to r.errorMessage
            }
            RecipientChannel.EMAIL -> {
                val subject = request.emailSubject ?: throw ValidationException("Temat e-maila nie może być pusty")
                val body = request.emailBody ?: throw ValidationException("Treść e-maila nie może być pusta")
                val r = emailProvider.send(request.address, subject, body)
                r.success to r.errorMessage
            }
        }
        return ResponseEntity.ok(mapOf("success" to result.first, "errorMessage" to result.second))
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @GetMapping("/settings")
    fun getSettings(): ResponseEntity<CampaignSettingsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(CampaignSettingsDto.fromDomain(service.getSettings(principal.studioId)))
    }

    @PutMapping("/settings")
    fun updateSettings(@RequestBody request: CampaignSettingsDto): ResponseEntity<CampaignSettingsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val updated = service.updateSettings(
            principal.studioId,
            CampaignSettings(
                studioId = principal.studioId,
                quietHoursStart = LocalTime.parse(request.quietHoursStart),
                quietHoursEnd = LocalTime.parse(request.quietHoursEnd),
                frequencyCapDays = request.frequencyCapDays,
                smsFooter = request.smsFooter?.takeIf { it.isNotBlank() },
                emailFooter = request.emailFooter?.takeIf { it.isNotBlank() },
                updatedAt = Instant.now()
            )
        )
        return ResponseEntity.ok(CampaignSettingsDto.fromDomain(updated))
    }
}
