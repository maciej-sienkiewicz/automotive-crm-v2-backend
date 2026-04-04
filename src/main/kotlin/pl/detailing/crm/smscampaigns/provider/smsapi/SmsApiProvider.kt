package pl.detailing.crm.smscampaigns.provider.smsapi

import org.slf4j.LoggerFactory
import pl.smsapi.OAuthClient
import pl.smsapi.api.SmsFactory
import pl.smsapi.exception.SmsapiException
import pl.smsapi.proxy.ProxyNative
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.provider.SmsProvider

/**
 * [SmsProvider] implementation backed by the official SMSAPI Java SDK (smsapi-lib:3.0.1).
 *
 * Responsibilities (SRP):
 *  - Translate the provider-agnostic [send] call into SMSAPI SDK calls.
 *  - Map SDK responses and exceptions to [SmsDeliveryResult].
 *  - Log every dispatch attempt for operational visibility.
 *
 * [SmsFactory] is created lazily so that a missing/empty [oauthToken]
 * in development (where [enabled] = false) does not cause a startup failure.
 */
class SmsApiProvider(
    private val properties: SmsApiProperties
) : SmsProvider {

    private val logger = LoggerFactory.getLogger(SmsApiProvider::class.java)

    private val smsFactory: SmsFactory by lazy {
        SmsFactory(
            OAuthClient(properties.oauthToken),
            ProxyNative(properties.apiUrl)
        )
    }

    override fun send(phoneNumber: String, message: String): SmsDeliveryResult {
        if (!properties.enabled) {
            logger.info("[SMS DISABLED] To: {} | Message: {}", phoneNumber, message)
            return SmsDeliveryResult.success("mock-disabled")
        }

        if (properties.whitelist.isNotEmpty() && phoneNumber !in properties.whitelist) {
            logger.warn("[SMS WHITELIST] Blocked send to {} — number not on whitelist", phoneNumber)
            return SmsDeliveryResult.failure("Number not on whitelist")
        }

        // SMSAPI expects numbers without the leading '+', e.g. "48100200300"
        val normalizedNumber = phoneNumber.removePrefix("+")

        return try {
            val action = smsFactory.actionSend(normalizedNumber, message)
                .apply { if (properties.senderName.isNotBlank()) setSender("2WAY") }

            val response = action.execute()
            val firstMessage = response.list.firstOrNull()

            if (firstMessage != null) {
                logger.info(
                    "SMS dispatched via SMSAPI | to={} shipmentId={} status={}",
                    phoneNumber, firstMessage.id, firstMessage.status
                )
                SmsDeliveryResult.success(firstMessage.id ?: "")
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
