package pl.detailing.crm.instagram.sync

import java.util.UUID

/**
 * Emitowane po zatwierdzeniu profilu konkurencji (status → ACTIVE).
 * Nasłuchiwacz uruchamia natychmiastowy sync tego profilu w tle.
 *
 * @param profileId  UUID globalnego profilu (instagram_profiles.id)
 * @param username   Username – tylko do celów logowania
 */
data class InstagramProfileApprovedEvent(
    val profileId: UUID,
    val username: String
)
