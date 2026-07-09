package pl.detailing.crm.payments.p24

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Thin client for the Przelewy24 REST API (v1).
 *
 * Flow:
 *   1. [registerTransaction] → P24 token; buyer is redirected to [Przelewy24Properties.paymentPageUrl]
 *   2. P24 POSTs a status notification to our webhook (urlStatus)
 *   3. We validate the notification signature ([notificationSign]) and confirm the
 *      transaction with [verifyTransaction] — only then is the payment final.
 *
 * All requests are signed with SHA-384 over a canonical JSON string that includes
 * the merchant CRC key, per P24 documentation.
 */
@Component
class Przelewy24Client(
    private val properties: Przelewy24Properties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val restTemplate: RestTemplate = RestTemplate()

    data class RegisterTransactionCommand(
        val sessionId: String,
        val amountCents: Long,
        val description: String,
        val email: String,
        val urlReturn: String,
        val urlStatus: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P24TokenData(val token: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P24RegisterResponse(val data: P24TokenData? = null, val responseCode: Int = -1)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P24VerifyStatus(val status: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P24VerifyResponse(val data: P24VerifyStatus? = null, val responseCode: Int = -1)

    /** Payload of the server-to-server status notification P24 sends to urlStatus. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P24Notification(
        val merchantId: Long = 0,
        val posId: Long = 0,
        val sessionId: String = "",
        val amount: Long = 0,
        val originAmount: Long = 0,
        val currency: String = "",
        val orderId: Long = 0,
        val methodId: Long = 0,
        val statement: String = "",
        val sign: String = ""
    )

    /** Registers a transaction and returns the P24 token used to build the payment page URL. */
    fun registerTransaction(command: RegisterTransactionCommand): String {
        val sign = sha384(
            """{"sessionId":"${command.sessionId}","merchantId":${properties.merchantId},"amount":${command.amountCents},"currency":"${properties.currency}","crc":"${properties.crc}"}"""
        )

        val body = mapOf(
            "merchantId" to properties.merchantId,
            "posId" to properties.posId,
            "sessionId" to command.sessionId,
            "amount" to command.amountCents,
            "currency" to properties.currency,
            "description" to command.description,
            "email" to command.email,
            "country" to properties.country,
            "language" to properties.language,
            "urlReturn" to command.urlReturn,
            "urlStatus" to command.urlStatus,
            "timeLimit" to 15,
            "encoding" to "UTF-8",
            "sign" to sign
        )

        val response = exchange("/api/v1/transaction/register", HttpMethod.POST, body, P24RegisterResponse::class.java)
        val token = response?.data?.token
        if (token.isNullOrBlank()) {
            throw Przelewy24Exception("Rejestracja transakcji w Przelewy24 nie powiodła się (responseCode=${response?.responseCode})")
        }

        logger.info("P24 transaction registered sessionId={} amount={} token={}", command.sessionId, command.amountCents, token.take(8) + "…")
        return token
    }

    /**
     * Confirms a notified transaction with P24. Must be called from the webhook after
     * signature and amount validation — P24 does not settle the payment until verified.
     */
    fun verifyTransaction(sessionId: String, orderId: Long, amountCents: Long) {
        val sign = sha384(
            """{"sessionId":"$sessionId","orderId":$orderId,"amount":$amountCents,"currency":"${properties.currency}","crc":"${properties.crc}"}"""
        )

        val body = mapOf(
            "merchantId" to properties.merchantId,
            "posId" to properties.posId,
            "sessionId" to sessionId,
            "amount" to amountCents,
            "currency" to properties.currency,
            "orderId" to orderId,
            "sign" to sign
        )

        val response = exchange("/api/v1/transaction/verify", HttpMethod.PUT, body, P24VerifyResponse::class.java)
        if (response?.data?.status != "success") {
            throw Przelewy24Exception("Weryfikacja transakcji w Przelewy24 nie powiodła się (sessionId=$sessionId, orderId=$orderId)")
        }

        logger.info("P24 transaction verified sessionId={} orderId={}", sessionId, orderId)
    }

    /** Computes the expected signature of a status notification. */
    fun notificationSign(n: P24Notification): String = sha384(
        """{"merchantId":${n.merchantId},"posId":${n.posId},"sessionId":"${n.sessionId}","amount":${n.amount},"originAmount":${n.originAmount},"currency":"${n.currency}","orderId":${n.orderId},"methodId":${n.methodId},"statement":"${n.statement}","crc":"${properties.crc}"}"""
    )

    fun isNotificationSignValid(notification: P24Notification): Boolean =
        MessageDigest.isEqual(
            notificationSign(notification).toByteArray(StandardCharsets.UTF_8),
            notification.sign.toByteArray(StandardCharsets.UTF_8)
        )

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun <T> exchange(path: String, method: HttpMethod, body: Any, responseType: Class<T>): T? {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBasicAuth(
                Base64.getEncoder().encodeToString(
                    "${properties.posId}:${properties.apiKey}".toByteArray(StandardCharsets.UTF_8)
                )
            )
        }

        return try {
            restTemplate.exchange(
                properties.apiBaseUrl + path,
                method,
                HttpEntity(objectMapper.writeValueAsString(body), headers),
                responseType
            ).body
        } catch (e: HttpStatusCodeException) {
            logger.error("P24 request {} {} failed: {} {}", method, path, e.statusCode, e.responseBodyAsString)
            throw Przelewy24Exception("Błąd komunikacji z Przelewy24: ${e.statusCode}")
        }
    }

    private fun sha384(input: String): String =
        MessageDigest.getInstance("SHA-384")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class Przelewy24Exception(message: String) : RuntimeException(message)
