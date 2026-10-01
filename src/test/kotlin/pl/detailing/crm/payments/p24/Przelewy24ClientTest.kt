package pl.detailing.crm.payments.p24

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Klient Przelewy24 bez sieci. Podpis P24 to SHA-384 z DOKŁADNEGO ciągu znaków JSON
 * (`json_encode` z `JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES`), więc testy porównują
 * podpis z wartością policzoną niezależnie — z ciągu napisanego ręcznie, a w dwóch miejscach
 * z wartością policzoną poza JVM (`printf … | sha384sum`). Gdyby serializator zaczął
 * escapować `/` albo polskie litery, każda notyfikacja ze `statement` zawierającym je byłaby
 * odrzucana jako sfałszowana, a każda rejestracja — odrzucana przez P24.
 */
class Przelewy24ClientTest {

    private val properties = Przelewy24Properties(
        sandbox = true,
        merchantId = 11111,
        posId = 22222,
        crc = "crc-secret",
        apiKey = "api-key"
    )
    private val restTemplate = RestTemplate()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(restTemplate).build()
    private val client = Przelewy24Client(properties, restTemplate)

    private val base = "https://sandbox.przelewy24.pl"
    /** base64("22222:api-key") — posId jako login, klucz API jako hasło. */
    private val expectedAuth = "Basic MjIyMjI6YXBpLWtleQ=="

    @AfterEach
    fun verifyServer() = server.verify()

    private fun sha384Hex(s: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-384").digest(s.toByteArray(StandardCharsets.UTF_8)))

    private fun notification(
        amount: Long = 4900,
        statement: String = "p24-A1-B2-C3",
        sign: String = ""
    ) = Przelewy24Client.P24Notification(
        merchantId = 11111,
        posId = 22222,
        sessionId = "sess-1",
        amount = amount,
        originAmount = 4900,
        currency = "PLN",
        orderId = 123456,
        methodId = 25,
        statement = statement,
        sign = sign
    )

    private fun rawBodyContains(expected: ByteArray) = RequestMatcher { request ->
        val body = (request as MockClientHttpRequest).bodyAsBytes
        val found = body.indices.any { i -> i + expected.size <= body.size && expected.indices.all { body[i + it] == expected[it] } }
        assertTrue(found, "treść żądania nie zawiera oczekiwanych bajtów UTF-8: ${String(body, StandardCharsets.UTF_8)}")
    }

    // ─── canonicalJson ────────────────────────────────────────────────────────

    @Nested
    inner class KanonicznyJson {

        @Test
        fun `zachowuje kolejnosc pol podana przez wolajacego`() {
            assertEquals(
                """{"sessionId":"s","merchantId":1,"amount":100,"currency":"PLN","crc":"c"}""",
                client.canonicalJson("sessionId" to "s", "merchantId" to 1L, "amount" to 100L, "currency" to "PLN", "crc" to "c")
            )
            // Odwrotna kolejność wejścia → odwrotna kolejność wyjścia (żadnego sortowania).
            assertEquals(
                """{"crc":"c","currency":"PLN","amount":100,"merchantId":1,"sessionId":"s"}""",
                client.canonicalJson("crc" to "c", "currency" to "PLN", "amount" to 100L, "merchantId" to 1L, "sessionId" to "s")
            )
        }

        @Test
        fun `nie escapuje ukosnika ani polskich liter`() {
            assertEquals(
                """{"statement":"Zapłata/za ŻÓŁĆ ąęś"}""",
                client.canonicalJson("statement" to "Zapłata/za ŻÓŁĆ ąęś")
            )
        }

        @Test
        fun `escapuje cudzyslow i ukosnik odwrotny`() {
            // Dawne sklejanie stringów wstawiało `"` surowo — podpis liczony z niepoprawnego JSON-a.
            assertEquals(
                """{"statement":"a\"b\\c"}""",
                client.canonicalJson("statement" to "a\"b\\c")
            )
        }

        @Test
        fun `liczby bez cudzyslowow`() {
            assertEquals("""{"amount":4900,"orderId":123456}""", client.canonicalJson("amount" to 4900L, "orderId" to 123456L))
        }
    }

    // ─── Podpis notyfikacji ───────────────────────────────────────────────────

    @Nested
    inner class PodpisNotyfikacji {

        @Test
        fun `podpis to SHA-384 z dokladnego JSON-a w kolejnosci z dokumentacji P24`() {
            val json = """{"merchantId":11111,"posId":22222,"sessionId":"sess-1","amount":4900,"originAmount":4900,""" +
                """"currency":"PLN","orderId":123456,"methodId":25,"statement":"p24-A1-B2-C3","crc":"crc-secret"}"""
            val sign = client.notificationSign(notification())

            assertEquals(sha384Hex(json), sign)
            // Policzone poza JVM: printf '%s' '<json>' | sha384sum
            assertEquals("b8a0d553cd27c5e90c48ca7064f1c01422d809569a966ce4f728a5bfe53ef26ea67880e80816888fa54b8bc0a7f97dd1", sign)
        }

        @Test
        fun `podpis z polskimi literami, ukosnikiem, cudzyslowem i ukosnikiem odwrotnym w statement`() {
            val statement = "Zapłata/Łódź \"Pakiet\" C:\\x"
            val json = """{"merchantId":11111,"posId":22222,"sessionId":"sess-1","amount":4900,"originAmount":4900,""" +
                """"currency":"PLN","orderId":123456,"methodId":25,"statement":"Zapłata/Łódź \"Pakiet\" C:\\x","crc":"crc-secret"}"""
            val sign = client.notificationSign(notification(statement = statement))

            assertEquals(sha384Hex(json), sign)
            assertEquals("b70dc26f9d3ae6c23b4285e1e3b0470f104dcaf754ee7bd84cc021a9c743b274cd3557ab1a4003c937cb970db1119741", sign)
        }

        @Test
        fun `poprawny podpis przechodzi`() {
            val n = notification()
            assertTrue(client.isNotificationSignValid(n.copy(sign = client.notificationSign(n))))
        }

        @Test
        fun `zmieniona kwota przy starym podpisie nie przechodzi`() {
            // Atak: przechwycona notyfikacja na 49 zł przerobiona na inną kwotę.
            val signed = notification().let { it.copy(sign = client.notificationSign(it)) }
            assertFalse(client.isNotificationSignValid(signed.copy(amount = 490000)))
            assertFalse(client.isNotificationSignValid(signed.copy(originAmount = 490000)))
            assertFalse(client.isNotificationSignValid(signed.copy(statement = "inny")))
        }

        @Test
        fun `brak podpisu i podpis innym kluczem CRC nie przechodza`() {
            val n = notification()
            assertFalse(client.isNotificationSignValid(n.copy(sign = "")))

            val otherCrc = Przelewy24Client(properties.copy(crc = "inny-crc"), RestTemplate())
            assertFalse(client.isNotificationSignValid(n.copy(sign = otherCrc.notificationSign(n))))
        }
    }

    // ─── Rejestracja transakcji ───────────────────────────────────────────────

    @Nested
    inner class Rejestracja {

        private val command = Przelewy24Client.RegisterTransactionCommand(
            sessionId = "sess-1",
            amountCents = 4900,
            description = "Pakiet Łódź/Śródmieście",
            email = "wlasciciel@example.com",
            urlReturn = "https://detailboost.pl/payments/result?orderId=1",
            urlStatus = "https://api.detailboost.pl/api/v1/payments/p24/status"
        )

        @Test
        fun `wysyla podpis, timeLimit i Basic Auth, zwraca token`() {
            val expectedSign = sha384Hex("""{"sessionId":"sess-1","merchantId":11111,"amount":4900,"currency":"PLN","crc":"crc-secret"}""")
            assertEquals("f2f94aab76b47783d854aa9d38963602b532a660bd41eb5197be33d2f15944c849e0f6f017f8ebc93acf3994abb6e4f0", expectedSign)

            server.expect(requestTo("$base/api/v1/transaction/register"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedAuth))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sign").value(expectedSign))
                .andExpect(jsonPath("$.timeLimit").value(15))
                .andExpect(jsonPath("$.merchantId").value(11111))
                .andExpect(jsonPath("$.posId").value(22222))
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.amount").value(4900))
                .andExpect(jsonPath("$.currency").value("PLN"))
                .andExpect(jsonPath("$.encoding").value("UTF-8"))
                .andExpect(jsonPath("$.urlStatus").value(command.urlStatus))
                .andExpect(jsonPath("$.urlReturn").value(command.urlReturn))
                // Bez `crc` w treści — klucz CRC jest tylko składnikiem podpisu, nigdy nie wychodzi.
                .andExpect(jsonPath("$.crc").doesNotExist())
                .andRespond(withSuccess("""{"data":{"token":"TOK-123"},"responseCode":0}""", MediaType.APPLICATION_JSON))

            assertEquals("TOK-123", client.registerTransaction(command))
        }

        @Test
        fun `timeLimit z konfiguracji`() {
            val custom = Przelewy24Client(properties.copy(transactionTimeLimitMinutes = 5), restTemplate)
            server.expect(requestTo("$base/api/v1/transaction/register"))
                .andExpect(jsonPath("$.timeLimit").value(5))
                .andRespond(withSuccess("""{"data":{"token":"TOK-5"},"responseCode":0}""", MediaType.APPLICATION_JSON))

            assertEquals("TOK-5", custom.registerTransaction(command))
        }

        @Test
        fun `opis z polskimi literami wysylany jako UTF-8`() {
            // Treść idzie jako String z Content-Type application/json — gdyby konwerter wybrał
            // ISO-8859-1, P24 dostałoby „?" w miejscu polskich liter.
            server.expect(requestTo("$base/api/v1/transaction/register"))
                .andExpect(rawBodyContains("Pakiet Łódź/Śródmieście".toByteArray(StandardCharsets.UTF_8)))
                .andRespond(withSuccess("""{"data":{"token":"TOK-PL"},"responseCode":0}""", MediaType.APPLICATION_JSON))

            assertEquals("TOK-PL", client.registerTransaction(command))
        }

        @Test
        fun `brak tokenu w odpowiedzi to wyjatek`() {
            server.expect(requestTo("$base/api/v1/transaction/register"))
                .andRespond(withSuccess("""{"data":null,"responseCode":-1}""", MediaType.APPLICATION_JSON))

            assertThrows<Przelewy24Exception> { client.registerTransaction(command) }
        }

        @Test
        fun `pusty token w odpowiedzi to wyjatek`() {
            server.expect(requestTo("$base/api/v1/transaction/register"))
                .andRespond(withSuccess("""{"data":{"token":""},"responseCode":0}""", MediaType.APPLICATION_JSON))

            assertThrows<Przelewy24Exception> { client.registerTransaction(command) }
        }

        @Test
        fun `blad 500 z P24 to Przelewy24Exception, nie surowy wyjatek HTTP`() {
            server.expect(requestTo("$base/api/v1/transaction/register"))
                .andRespond(withServerError().body("""{"error":"internal"}""").contentType(MediaType.APPLICATION_JSON))

            assertThrows<Przelewy24Exception> { client.registerTransaction(command) }
        }
    }

    // ─── Weryfikacja transakcji ───────────────────────────────────────────────

    @Nested
    inner class Weryfikacja {

        @Test
        fun `wysyla PUT z podpisem po sessionId, orderId, amount, currency i konczy sie bez wyjatku przy success`() {
            val expectedSign = sha384Hex("""{"sessionId":"sess-1","orderId":123456,"amount":4900,"currency":"PLN","crc":"crc-secret"}""")
            assertEquals("831d8fbb76cb0609c5964e10ef8007d2cf287f91bef9c9757131e3f359d72df98d2c19e95e95c5fbb26b0607cc3c2ef3", expectedSign)

            server.expect(requestTo("$base/api/v1/transaction/verify"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedAuth))
                .andExpect(jsonPath("$.sign").value(expectedSign))
                .andExpect(jsonPath("$.orderId").value(123456))
                .andExpect(jsonPath("$.amount").value(4900))
                .andExpect(jsonPath("$.merchantId").value(11111))
                .andExpect(jsonPath("$.posId").value(22222))
                .andRespond(withSuccess("""{"data":{"status":"success"},"responseCode":0}""", MediaType.APPLICATION_JSON))

            client.verifyTransaction("sess-1", 123456, 4900)
        }

        @Test
        fun `status inny niz success to wyjatek`() {
            server.expect(requestTo("$base/api/v1/transaction/verify"))
                .andRespond(withSuccess("""{"data":{"status":"error"},"responseCode":0}""", MediaType.APPLICATION_JSON))

            assertThrows<Przelewy24Exception> { client.verifyTransaction("sess-1", 123456, 4900) }
        }

        @Test
        fun `brak danych w odpowiedzi to wyjatek`() {
            server.expect(requestTo("$base/api/v1/transaction/verify"))
                .andRespond(withSuccess("""{"responseCode":0}""", MediaType.APPLICATION_JSON))

            assertThrows<Przelewy24Exception> { client.verifyTransaction("sess-1", 123456, 4900) }
        }

        @Test
        fun `odrzucenie przez P24 (400) to Przelewy24Exception`() {
            server.expect(requestTo("$base/api/v1/transaction/verify"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""{"error":"Incorrect sign","code":400}""").contentType(MediaType.APPLICATION_JSON))

            assertThrows<Przelewy24Exception> { client.verifyTransaction("sess-1", 123456, 4900) }
        }
    }

    // ─── Stan transakcji po sessionId ─────────────────────────────────────────

    @Nested
    inner class StanTransakcji {

        @Test
        fun `zwraca stan transakcji i pomija dane platnika`() {
            server.expect(requestTo("$base/api/v1/transaction/by/sessionId/sess-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, expectedAuth))
                .andRespond(
                    withSuccess(
                        """{"data":{"orderId":123456,"sessionId":"sess-1","status":2,"amount":4900,"currency":"PLN",
                           "clientEmail":"jan@example.com","clientName":"Jan Kowalski","statement":"p24"},"responseCode":0}""",
                        MediaType.APPLICATION_JSON
                    )
                )

            val state = client.getTransactionBySessionId("sess-1")
            assertNotNull(state)
            assertEquals(Przelewy24Client.P24TransactionData(123456, "sess-1", 2, 4900, "PLN"), state)
        }

        @Test
        fun `404 - P24 nie zna transakcji, wynik null`() {
            server.expect(requestTo("$base/api/v1/transaction/by/sessionId/sess-unknown"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("""{"error":"Transaction not found","code":404}""").contentType(MediaType.APPLICATION_JSON))

            assertNull(client.getTransactionBySessionId("sess-unknown"))
        }

        @Test
        fun `500 - wyjatek, nie null`() {
            // Null znaczy „P24 nie zna transakcji" i pozwala rekoncyliacji wygasić zamówienie.
            // Błąd serwera nie może udawać tej odpowiedzi — opłacone zamówienie wygasłoby.
            server.expect(requestTo("$base/api/v1/transaction/by/sessionId/sess-1"))
                .andRespond(withServerError())

            assertThrows<Przelewy24Exception> { client.getTransactionBySessionId("sess-1") }
        }
    }

    // ─── Domyślny RestTemplate ────────────────────────────────────────────────

    @Test
    fun `domyslny RestTemplate ma timeouty polaczenia i odczytu (audyt P4)`() {
        // Wszystkie @Scheduled biegną na jednym wątku — wiszące P24 bez timeoutu wstrzymałoby każdy job.
        val factory = Przelewy24Client.defaultRestTemplate().requestFactory
        assertTrue(factory is SimpleClientHttpRequestFactory, "nieoczekiwana fabryka: ${factory.javaClass}")
        assertEquals(3_000, ReflectionTestUtils.getField(factory, "connectTimeout"))
        assertEquals(10_000, ReflectionTestUtils.getField(factory, "readTimeout"))
    }
}
