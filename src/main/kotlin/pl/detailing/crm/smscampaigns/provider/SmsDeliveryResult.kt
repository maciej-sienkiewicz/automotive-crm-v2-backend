package pl.detailing.crm.smscampaigns.provider

/**
 * Immutable result of a single SMS dispatch attempt.
 * Keeps the [SmsProvider] contract free of checked exceptions.
 */
data class SmsDeliveryResult(
    val success: Boolean,
    val externalMessageId: String?,
    val errorMessage: String?
) {
    companion object {
        fun success(externalMessageId: String) = SmsDeliveryResult(
            success = true,
            externalMessageId = externalMessageId,
            errorMessage = null
        )

        fun failure(errorMessage: String) = SmsDeliveryResult(
            success = false,
            externalMessageId = null,
            errorMessage = errorMessage
        )
    }
}
