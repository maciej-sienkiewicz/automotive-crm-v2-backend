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
 *
 * SENDER RESOLUTION — there is no global sender name any more. A studio either has its
 * own SMSAPI-confirmed sender ID (passed in as [send]'s `senderName`), or the message goes
 * out as ECO: SMSAPI delivers it from one of its own numbers, so the recipient sees a phone
 * number instead of an alphanumeric header. That is deliberate — a shared placeholder header
 * such as "Test" must never reach a production customer.
 */
class SmsApiProvider(
    private val properties: SmsApiProperties
) : SmsProvider {

    private val logger = LoggerFactory.getLogger(SmsApiProvider::class.java)

    private companion object {
        /**
         * SMSAPI treats `from` as the message type as well as the header: this reserved value
         * sends the message from one of SMSAPI's own numbers, with no sender name at all.
         */
        const val ECO_SENDER = "2WAY"
    }

    private val smsFactory: SmsFactory by lazy {
        SmsFactory(
            OAuthClient(properties.oauthToken),
            ProxyNative(properties.apiUrl)
        )
    }

    override fun send(phoneNumber: String, message: String, senderName: String?): SmsDeliveryResult {
        val sender = senderName?.trim()?.takeIf { it.isNotEmpty() } ?: ECO_SENDER
        val isEco = sender.equals(ECO_SENDER, ignoreCase = true)

        if (!properties.enabled) {
            logger.info("[SMS DISABLED] To: {} | Sender: {} | Message: {}", phoneNumber, sender, message)
            return SmsDeliveryResult.success("mock-disabled")
        }

        val e164Number = phoneNumber.normalizePhone()
        // SMSAPI expects numbers without the leading '+', e.g. "48100200300"
        val normalizedNumber = e164Number.removePrefix("+")

        return try {
            val action = smsFactory.actionSend(normalizedNumber, message)
                .apply {
                    setSender(sender)
                    // ECO does not carry Polish diacritics: without normalisation the message is
                    // billed as UCS-2 (70 chars per part) or arrives mangled, so fold it to ASCII.
                    if (isEco) setNormalize(true)
                }

            val response = action.execute()
            val firstMessage = response.list.firstOrNull()

            if (firstMessage != null) {
                logger.info(
                    "SMS dispatched via SMSAPI | to={} sender={} shipmentId={} status={}",
                    phoneNumber, sender, firstMessage.id, firstMessage.status
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

/** Strip all whitespace and dashes so "+48 888 915 358" equals "+48888915358". */
private fun String.normalizePhone(): String = replace(Regex("[\\s\\-]"), "")
