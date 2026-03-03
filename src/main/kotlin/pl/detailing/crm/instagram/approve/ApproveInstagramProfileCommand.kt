package pl.detailing.crm.instagram.approve

import pl.detailing.crm.shared.StudioInstagramProfileId
import pl.detailing.crm.shared.StudioId

data class ApproveInstagramProfileCommand(
    val studioId: StudioId,
    val studioProfileId: StudioInstagramProfileId
)
