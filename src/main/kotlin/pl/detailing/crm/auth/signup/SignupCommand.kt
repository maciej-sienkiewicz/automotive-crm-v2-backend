package pl.detailing.crm.auth.signup

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

data class SignupCommand(
    val firstName: String,
    val lastName: String,
    val studioName: String,
    val email: String,
    val passwordHash: String
)

data class SignupResult(
    val userId: UserId,
    val studioId: StudioId,
    val email: String,
    val firstName: String,
    val lastName: String,
    val trialEndsAt: String
)