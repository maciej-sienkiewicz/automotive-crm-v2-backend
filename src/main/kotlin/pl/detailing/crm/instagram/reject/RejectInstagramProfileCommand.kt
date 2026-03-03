package pl.detailing.crm.instagram.reject

import pl.detailing.crm.shared.StudioInstagramProfileId
import pl.detailing.crm.shared.StudioId

data class RejectInstagramProfileCommand(
    val studioId: StudioId,
    val studioProfileId: StudioInstagramProfileId
)
