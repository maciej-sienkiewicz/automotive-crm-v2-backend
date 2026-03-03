package pl.detailing.crm.instagram.remove

import pl.detailing.crm.shared.StudioInstagramProfileId
import pl.detailing.crm.shared.StudioId

data class RemoveInstagramProfileCommand(
    val studioId: StudioId,
    val studioProfileId: StudioInstagramProfileId
)
