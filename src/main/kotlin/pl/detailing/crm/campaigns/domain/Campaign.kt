package pl.detailing.crm.campaigns.domain

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

enum class CampaignKind { ONE_TIME, AUTOMATIC }

enum class CampaignChannel { SMS, EMAIL, BOTH }

enum class CampaignStatus {
    // ONE_TIME: DRAFT → SCHEDULED → SENDING → COMPLETED (+ CANCELLED, FAILED)
    // AUTOMATIC: DRAFT → ACTIVE ⇄ PAUSED → ARCHIVED
    DRAFT, SCHEDULED, SENDING, COMPLETED, CANCELLED, FAILED,
    ACTIVE, PAUSED, ARCHIVED;

    val isEditable: Boolean
        get() = this in setOf(DRAFT, SCHEDULED, ACTIVE, PAUSED)
}

// ─── Audience definition (persisted as JSONB) ────────────────────────────────

data class VehicleBrandFilter(val brand: String, val model: String? = null)

data class AudienceCriteria(
    // Wizyty
    val visitCountMin: Int? = null,
    val visitCountMax: Int? = null,
    val lastVisitOlderThanDays: Int? = null,
    val lastVisitNewerThanDays: Int? = null,
    // Przychody (grosze, suma final_price_gross zakończonych wizyt)
    val revenueTotalGrossMin: Long? = null,
    val revenueTotalGrossMax: Long? = null,
    // Usługi
    val servicesUsedAnyOf: List<UUID> = emptyList(),
    val servicesUsedNoneOf: List<UUID> = emptyList(),
    val serviceLastUsedOlderThanDays: Int? = null,
    // Pojazdy
    val vehicleBrands: List<VehicleBrandFilter> = emptyList(),
    val vehicleYearMin: Int? = null,
    val vehicleYearMax: Int? = null,
    // Klient
    val customerType: CustomerTypeFilter = CustomerTypeFilter.ALL,
    val customerCreatedAfter: Instant? = null,
    /**
     * Czy obejmować klientów bez imienia i nazwiska (przyjęcia „na numer telefonu").
     *
     * Domyślne `true` jest wyłącznie zgodnością wsteczną: kampanie zapisane przed
     * wprowadzeniem tego pola nie miały takiego filtra i ich grupa odbiorców nie ma
     * prawa zmienić się sama, w locie. Kreator ustawia `false` dla nowych kampanii —
     * wiadomość z „Cześć !" zamiast imienia szkodzi bardziej, niż pomaga, a i tak
     * nie da się takiego klienta rozpoznać na liście.
     */
    val includeUnnamedCustomers: Boolean = true,
    // Ręczna korekta
    val includeCustomerIds: List<UUID> = emptyList(),
    val excludeCustomerIds: List<UUID> = emptyList()
)

enum class CustomerTypeFilter { ALL, INDIVIDUAL, COMPANY }

// ─── Trigger (AUTOMATIC only, persisted as JSONB) ────────────────────────────

data class TriggerConfig(
    val serviceIds: List<UUID>,
    val afterDays: Int,
    val sendTime: String = "10:00",           // HH:mm, czas lokalny studia
    val onlyIfNoVisitSince: Boolean = true
) {
    init {
        if (afterDays < 1) throw ValidationException("Liczba dni musi być większa od zera")
        if (serviceIds.isEmpty()) throw ValidationException("Wybierz przynajmniej jedną usługę")
    }
}

// ─── Aggregate ───────────────────────────────────────────────────────────────

data class Campaign(
    val id: UUID,
    val studioId: StudioId,
    val name: String,
    val kind: CampaignKind,
    val channel: CampaignChannel,
    val status: CampaignStatus,
    val audience: AudienceCriteria,
    val smsTemplate: String?,
    val emailSubject: String?,
    val emailBody: String?,
    val scheduledAt: Instant?,
    val trigger: TriggerConfig?,
    val recipientsTotal: Int = 0,
    val recipientsSent: Int = 0,
    val recipientsFailed: Int = 0,
    val recipientsSkipped: Int = 0,
    val creditsSpent: Int = 0,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null
) {
    fun requireEditable() {
        if (!status.isEditable) {
            throw ValidationException("Kampanii w tym statusie nie można edytować")
        }
    }

    fun validateContent() {
        val wantsSms = channel == CampaignChannel.SMS || channel == CampaignChannel.BOTH
        val wantsEmail = channel == CampaignChannel.EMAIL || channel == CampaignChannel.BOTH
        if (wantsSms && smsTemplate.isNullOrBlank()) {
            throw ValidationException("Treść SMS nie może być pusta")
        }
        if (wantsEmail && (emailSubject.isNullOrBlank() || emailBody.isNullOrBlank())) {
            throw ValidationException("Temat i treść e-maila nie mogą być puste")
        }
        if (kind == CampaignKind.AUTOMATIC && trigger == null) {
            throw ValidationException("Kampania automatyczna wymaga warunku wysyłki")
        }
    }
}
