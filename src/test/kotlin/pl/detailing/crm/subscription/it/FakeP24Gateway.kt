package pl.detailing.crm.subscription.it

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.http.HttpMethod
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpResponse
import pl.detailing.crm.payments.p24.Przelewy24Client
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Przelewy24 po drugiej stronie `RestTemplate` — bez sieci, ze stanem, który test ustawia.
 *
 * Zamiast `MockRestServiceServer` z listą oczekiwań: scenariusze płatności wołają P24 różną
 * liczbę razy w zależności od przeplotu wątków (duplikat notyfikacji, rekoncyliacja w tle),
 * a test ma sprawdzać EFEKT w bazie, nie kolejność zapytań HTTP. Bramka pamięta transakcje
 * jak prawdziwe P24: rejestracja zakłada transakcję, wpłata (`pay`) nadaje jej orderId
 * i status 1, udany `verify` — status 2.
 */
class FakeP24Gateway : ClientHttpRequestInterceptor {

    data class Call(val method: HttpMethod, val path: String, val body: String)

    data class Transaction(val sessionId: String, val amount: Long, var orderId: Long = 0, var status: Int = 0)

    private val mapper = jacksonObjectMapper()
    private val nextOrderId = AtomicInteger(900_000)

    val calls: MutableList<Call> = CopyOnWriteArrayList()
    val transactions: MutableMap<String, Transaction> = ConcurrentHashMap()

    /** Rejestracja odpowiada błędem 500 (P24 leży). */
    @Volatile var registerFails = false
    /** `verify` odpowiada błędem 400 (np. P24 nie potwierdza). */
    @Volatile var verifyFails = false
    /** Zapytanie o stan transakcji odpowiada błędem 503. */
    @Volatile var lookupFails = false
    /** Wołane przy każdym `verify` PRZED odpowiedzią — miejsce na bramkę wyścigu w teście. */
    @Volatile var beforeVerify: (sessionId: String) -> Unit = {}

    fun reset() {
        calls.clear()
        transactions.clear()
        registerFails = false
        verifyFails = false
        lookupFails = false
        beforeVerify = {}
    }

    /** Kupujący płaci w P24: transakcja dostaje orderId i status „wpłacona". Zwraca orderId P24. */
    fun pay(sessionId: String, amount: Long? = null): Long {
        val transaction = transactions.getOrPut(sessionId) { Transaction(sessionId, amount ?: 0) }
        if (transaction.orderId == 0L) transaction.orderId = nextOrderId.incrementAndGet().toLong()
        transaction.status = maxOf(transaction.status, 1)
        return transaction.orderId
    }

    fun count(method: HttpMethod, pathPrefix: String): Int = calls.count { it.method == method && it.path.startsWith(pathPrefix) }

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        val path = request.uri.path
        val method = request.method
        val text = String(body)
        calls += Call(method, path, text)

        return when {
            method == HttpMethod.POST && path == "/api/v1/transaction/register" -> register(text)
            method == HttpMethod.PUT && path == "/api/v1/transaction/verify" -> verify(text)
            method == HttpMethod.GET && path.startsWith("/api/v1/transaction/by/sessionId/") ->
                lookup(path.removePrefix("/api/v1/transaction/by/sessionId/"))
            else -> json(HttpStatus.NOT_FOUND, mapOf("error" to "unknown endpoint $method $path"))
        }
    }

    private fun register(body: String): ClientHttpResponse {
        if (registerFails) return json(HttpStatus.INTERNAL_SERVER_ERROR, mapOf("error" to "P24 unavailable"))
        val request: Map<String, Any?> = mapper.readValue(body)
        val sessionId = request["sessionId"] as String
        val amount = (request["amount"] as Number).toLong()
        transactions.putIfAbsent(sessionId, Transaction(sessionId, amount))
        return json(HttpStatus.OK, mapOf("data" to mapOf("token" to "TOKEN-$sessionId"), "responseCode" to 0))
    }

    private fun verify(body: String): ClientHttpResponse {
        val request: Map<String, Any?> = mapper.readValue(body)
        val sessionId = request["sessionId"] as String
        beforeVerify(sessionId)
        if (verifyFails) return json(HttpStatus.BAD_REQUEST, mapOf("error" to "verify rejected", "code" to 400))
        val transaction = transactions[sessionId]
            ?: return json(HttpStatus.BAD_REQUEST, mapOf("error" to "unknown transaction", "code" to 400))
        transaction.status = 2
        return json(HttpStatus.OK, mapOf("data" to mapOf("status" to "success"), "responseCode" to 0))
    }

    private fun lookup(sessionId: String): ClientHttpResponse {
        if (lookupFails) return json(HttpStatus.SERVICE_UNAVAILABLE, mapOf("error" to "P24 unavailable"))
        val transaction = transactions[sessionId]
            ?: return json(HttpStatus.NOT_FOUND, mapOf("error" to "Transaction not found", "code" to 404))
        return json(
            HttpStatus.OK,
            mapOf(
                "data" to Przelewy24Client.P24TransactionData(
                    orderId = transaction.orderId,
                    sessionId = transaction.sessionId,
                    status = transaction.status,
                    amount = transaction.amount,
                    currency = "PLN"
                ),
                "responseCode" to 0
            )
        )
    }

    private fun json(status: HttpStatus, body: Any): ClientHttpResponse =
        MockClientHttpResponse(mapper.writeValueAsBytes(body), status).apply {
            headers.contentType = MediaType.APPLICATION_JSON
        }

}
