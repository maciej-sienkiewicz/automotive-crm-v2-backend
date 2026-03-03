package pl.detailing.crm.instagram.add

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class AddInstagramProfileCommand(
    val studioId: StudioId,
    val userId: UserId,
    val username: String
)
