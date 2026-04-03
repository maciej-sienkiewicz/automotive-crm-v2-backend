package pl.detailing.crm.email.provider

data class EmailDeliveryResult(
    val success: Boolean,
    val messageId: String?,
    val errorMessage: String?
) {
    companion object {
        fun success(messageId: String) = EmailDeliveryResult(
            success = true,
            messageId = messageId,
            errorMessage = null
        )

        fun failure(error: String) = EmailDeliveryResult(
            success = false,
            messageId = null,
            errorMessage = error
        )
    }
}
