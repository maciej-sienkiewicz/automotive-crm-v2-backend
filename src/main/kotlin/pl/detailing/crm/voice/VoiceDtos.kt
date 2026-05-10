package pl.detailing.crm.voice

data class VoiceContextResponse(
    val firstName: String,
    val studioName: String
)

data class VoiceResultResponse(
    val id: String,
    val message: String
)

data class VoiceErrorResponse(
    val error: String
)

data class MobileTokenResponse(
    val token: String,
    val url: String
)
