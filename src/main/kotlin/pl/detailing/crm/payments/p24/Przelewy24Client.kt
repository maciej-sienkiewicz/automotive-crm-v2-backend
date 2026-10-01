package pl.detailing.crm.payments.p24

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * Thin client for the Przelewy24 REST API (v1).
 *
 * Flow:
 *   1. [registerTransaction] → P24 token; buyer is redirected to [Przelewy24Properties.paymentPageUrl]
 *   2. P24 POSTs a status notification to our webhook (urlStatus)
 *   3. We validate the notification signature ([notificationSign]) and confirm the
 *      transaction with [verifyTransaction] — only then is the payment final.
 *   4. [getTransactionBySessionId] — stan transakcji wprost z P24; używa go rekoncyliacja,
 *      gdy notyfikacja zginęła albo nie przyszła.
 *
 * All requests are signed with SHA-384 over a canonical JSON string that includes
 * the merchant CRC key, per P24 documentation.
 *
 * Dwie rzeczy, których brak był audytowym zarzutem (P4, P8):
 *  - TIMEOUTY. Dawny `RestTemplate()` nie miał żadnych, a wywołanie szło m.in. z wnętrza
 *    transakcji bazy — wolne P24 trzymało wątek i połączenie z puli bez limitu. Do tego
 *    wszystkie joby `@Scheduled` w tej aplikacji biegną na JEDNYM wątku, więc wiszące
 *    wywołanie z workera rekoncyliacji wstrzymywałoby każdy inny job.
 *  - PODPIS przez serializator JSON, nie sklejanie stringów. P24 liczy skrót z
 *    `json_encode(..., JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)` — Jackson domyślnie
 *    zachowuje się tak samo (bez escapowania `/` i znaków spoza ASCII), a w odróżnieniu od
 *    sklejania escapuje cudzysłów i ukośnik odwrotny w `statement`.
 */
@Component
class Przelewy24Client internal constructor(
    private val properties: Przelewy24Properties,
    private val restTemplate: RestTemplate
) {
    @Autowired
    constructor(properties: Przelewy24Properties) : this(properties, defaultRestTemplate())

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

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

    /**
     * Stan transakcji z `GET /api/v1/transaction/by/sessionId/{sessionId}`. Celowo bez danych
     * płatnika (`clientEmail`, `clientName`…) — nie są nam potrzebne, a odpowiedź nie ma
     * powodu nieść PII dalej.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P24TransactionData(
        val orderId: Long = 0,
        val sessionId: String = "",
        /** 0 — brak wpłaty, 1 — wpłacona, niezweryfikowana, 2 — wpłacona i zweryfikowana, 3 — zwrócona. */
        val status: Int = -1,
        val amount: Long = 0,
        val currency: String = ""
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class P24TransactionBySessionResponse(val data: P24TransactionData? = null, val responseCode: Int = -1)

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
            canonicalJson(
                "sessionId" to command.sessionId,
                "merchantId" to properties.merchantId,
                "amount" to command.amountCents,
                "currency" to properties.currency,
                "crc" to properties.crc
            )
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
            "timeLimit" to properties.transactionTimeLimitMinutes,
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
     * Confirms a notified transaction with P24. Must be called after signature and amount
     * validation — P24 does not settle the payment until verified, and keeps re-sending the
     * notification (3, 5, 15, 30, 60, 150, 450 min) until a verify succeeds.
     */
    fun verifyTransaction(sessionId: String, orderId: Long, amountCents: Long) {
        val sign = sha384(
            canonicalJson(
                "sessionId" to sessionId,
                "orderId" to orderId,
                "amount" to amountCents,
                "currency" to properties.currency,
                "crc" to properties.crc
            )
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

    /**
     * Stan transakcji w P24 po naszym `sessionId`; null, gdy P24 jej nie zna (404 — np.
     * rejestracja nie doszła do skutku).
     */
    fun getTransactionBySessionId(sessionId: String): P24TransactionData? {
        val headers = authHeaders()
        return try {
            restTemplate.exchange(
                properties.apiBaseUrl + "/api/v1/transaction/by/sessionId/{sessionId}",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                P24TransactionBySessionResponse::class.java,
                sessionId
            ).body?.data
        } catch (e: HttpStatusCodeException) {
            if (e.statusCode == HttpStatus.NOT_FOUND) return null
            logger.error("P24 transaction lookup sessionId={} failed: {} {}", sessionId, e.statusCode, e.responseBodyAsString)
            throw Przelewy24Exception("Błąd komunikacji z Przelewy24: ${e.statusCode}")
        }
    }

    /** Computes the expected signature of a status notification. */
    fun notificationSign(n: P24Notification): String = sha384(
        canonicalJson(
            "merchantId" to n.merchantId,
            "posId" to n.posId,
            "sessionId" to n.sessionId,
            "amount" to n.amount,
            "originAmount" to n.originAmount,
            "currency" to n.currency,
            "orderId" to n.orderId,
            "methodId" to n.methodId,
            "statement" to n.statement,
            "crc" to properties.crc
        )
    )

    fun isNotificationSignValid(notification: P24Notification): Boolean =
        MessageDigest.isEqual(
            notificationSign(notification).toByteArray(StandardCharsets.UTF_8),
            notification.sign.toByteArray(StandardCharsets.UTF_8)
        )

    /**
     * JSON w kolejności pól z dokumentacji P24 — skrót liczy się z dokładnego ciągu znaków,
     * więc kolejność jest częścią podpisu (stąd `LinkedHashMap`, nie `mapOf` z niegwarantowaną
     * kolejnością w innych implementacjach).
     */
    internal fun canonicalJson(vararg fields: Pair<String, Any>): String =
        objectMapper.writeValueAsString(LinkedHashMap<String, Any>().apply { fields.forEach { put(it.first, it.second) } })

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun authHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        setBasicAuth(
            Base64.getEncoder().encodeToString(
                "${properties.posId}:${properties.apiKey}".toByteArray(StandardCharsets.UTF_8)
            )
        )
    }

    private fun <T> exchange(path: String, method: HttpMethod, body: Any, responseType: Class<T>): T? =
        try {
            restTemplate.exchange(
                properties.apiBaseUrl + path,
                method,
                HttpEntity(objectMapper.writeValueAsString(body), authHeaders()),
                responseType
            ).body
        } catch (e: HttpStatusCodeException) {
            logger.error("P24 request {} {} failed: {} {}", method, path, e.statusCode, e.responseBodyAsString)
            throw Przelewy24Exception("Błąd komunikacji z Przelewy24: ${e.statusCode}")
        }

    private fun sha384(input: String): String =
        MessageDigest.getInstance("SHA-384")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

        fun defaultRestTemplate(): RestTemplate = RestTemplate(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            }
        )
    }
}

class Przelewy24Exception(message: String) : RuntimeException(message)
