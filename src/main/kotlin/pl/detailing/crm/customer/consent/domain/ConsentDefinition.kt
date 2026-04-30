package pl.detailing.crm.customer.consent.domain

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * A consent definition represents a persistent customer agreement (e.g., "RODO", "Marketing Consent").
 *
 * Unlike visit protocols that are generated fresh per visit, a consent is signed once per customer
 * and remains valid until revoked or a new version requiring re-sign is published.
 *
 * marketingChannels declares which communication channels this consent covers (EMAIL, SMS).
 * At most one active consent per studio may cover a given channel.
 */
data class ConsentDefinition(
    val id: ConsentDefinitionId,
    val studioId: StudioId,
    val name: String,
    val description: String?,
    val stage: ProtocolStage,
    val marketingChannels: Set<MarketingChannel> = emptySet(),
    val displayOrder: Int,
    val isActive: Boolean,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)
