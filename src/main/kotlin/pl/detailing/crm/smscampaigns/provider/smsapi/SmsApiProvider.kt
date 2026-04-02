package pl.detailing.crm.smscampaigns.provider.smsapi

import org.slf4j.LoggerFactory
import pl.smsapi.OAuthClient
import pl.smsapi.api.SmsApi
import pl.smsapi.exception.SmsapiException
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider

/**
 * [SmsProvider] implementation backed by the official SMSAPI Java SDK.
 *
 * Responsibilities (SRP):
 *  - Translate the provider-agnostic [send] call into SMSAPI SDK calls.
 *  - Map SDK responses and exceptions to [SmsDeliveryResult].
 *  - Log every dispatch attempt for operational visibility.
 *
 * The [SmsApi] client is created lazily so that a missing/empty [oauthToken]
 * in development (where [enabled] = false) does not cause a startup failure.
 */
class SmsApiProvider(
    private val properties: SmsApiProperties
) : SmsProvider {

    private val logger = LoggerFactory.getLogger(SmsApiProvider::class.java)

    private val api: SmsApi by lazy {
        SmsApi(OAuthClient(properties.oauthToken))
    }

    override fun send(phoneNumber: String, message: String): SmsDeliveryResult {
        if (!properties.enabled) {
            logger.info("[SMS DISABLED] To: {} | Message: {}", phoneNumber, message)
            return SmsDeliveryResult.success("mock-disabled")
        }

        // SMSAPI expects numbers without the leading '+', e.g. "48100200300"
        val normalizedNumber = phoneNumber.removePrefix("+")

        return try {
            val action = api.actionSend()
                .setTo(normalizedNumber)
                .setMessage(message)
                .apply { if (properties.senderName.isNotBlank()) setSender(properties.senderName) }

            val response = api.execute(action)
            val firstResult = response.list.firstOrNull()

            if (firstResult != null) {
                logger.info(
                    "SMS dispatched via SMSAPI | to={} smsId={} status={}",
                    phoneNumber, firstResult.smsId, firstResult.status
                )
                SmsDeliveryResult.success(firstResult.smsId ?: "")
            } else {
                logger.warn("SMSAPI returned empty result list for number={}", phoneNumber)
                SmsDeliveryResult.failure("Empty result list returned by SMSAPI")
            }
        } catch (ex: SmsapiException) {
            logger.error("SMSAPI error sending to {}: {}", phoneNumber, ex.message, ex)
            SmsDeliveryResult.failure(ex.message ?: "Unknown SMSAPI error")
        }
    }
}
