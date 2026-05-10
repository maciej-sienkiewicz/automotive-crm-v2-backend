package pl.detailing.crm.voice

data class VoiceLeadRequest(
    val token: String,
    val phoneNumber: String? = null,
    val text: String
)

data class VoiceNoteRequest(
    val token: String,
    val text: String
)

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
