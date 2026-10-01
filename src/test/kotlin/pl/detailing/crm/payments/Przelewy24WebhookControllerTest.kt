package pl.detailing.crm.payments

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.payments.notification.NotificationOutcome
import pl.detailing.crm.payments.notification.PaymentNotificationProcessor
import pl.detailing.crm.payments.p24.Przelewy24Client
import pl.detailing.crm.payments.p24.Przelewy24Properties
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Webhook notyfikacji Przelewy24. Kolejność po audycie (P1, P2, P5): podpis → trwały zapis
 * → obsługa. Kody HTTP mają tu znaczenie biznesowe: 500 wolno zwrócić WYŁĄCZNIE, gdy zapis
 * się nie udał (wtedy ponowienie P24 jest jedyną kopią informacji o płatności); błąd obsługi
 * po zapisie to nasze ponowienie, więc P24 dostaje 200.
 *
 * P24 opisuje notyfikację raz jako JSON, raz jako formularz — dlatego oba formaty muszą
 * trafić do TEJ SAMEJ, kompletnie wypełnionej notyfikacji. Wiązanie formularza z niemutowalną
 * klasą danych Kotlina (`val` + wartości domyślne) przez `@ModelAttribute` nie jest oczywiste:
 * gdyby Spring utworzył obiekt konstruktorem bezargumentowym i nie umiał ustawić pól `val`,
 * każda notyfikacja formularzowa miałaby puste pola, nie przeszłaby podpisu i opłacone
 * zamówienie nigdy by się nie zrealizowało — bez żadnego błędu poza 400 w logu.
 */
class Przelewy24WebhookControllerTest {

    private val p24Client = mockk<Przelewy24Client>()
    private val processor = mockk<PaymentNotificationProcessor>()
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(Przelewy24WebhookController(p24Client, processor)).build()

    private val url = "/api/v1/payments/p24/status"
    private val notificationId = UUID.fromString("00000000-0000-0000-0000-00000000a001")

    private val expected = Przelewy24Client.P24Notification(
        merchantId = 11111,
        posId = 22222,
        sessionId = "sess-1",
        amount = 4900,
        originAmount = 4900,
        currency = "PLN",
        orderId = 123456,
        methodId = 25,
        statement = "p24-A1-B2-C3",
        sign = "abc123"
    )

    private fun formBody(n: Przelewy24Client.P24Notification, extra: Map<String, String> = emptyMap()): String =
        (linkedMapOf(
            "merchantId" to n.merchantId.toString(),
            "posId" to n.posId.toString(),
            "sessionId" to n.sessionId,
            "amount" to n.amount.toString(),
            "originAmount" to n.originAmount.toString(),
            "currency" to n.currency,
            "orderId" to n.orderId.toString(),
            "methodId" to n.methodId.toString(),
            "statement" to n.statement,
            "sign" to n.sign
        ) + extra).entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }

    private fun stubHappyPath(captured: io.mockk.CapturingSlot<Przelewy24Client.P24Notification>) {
        every { p24Client.isNotificationSignValid(capture(captured)) } returns true
        every { processor.record(any()) } returns notificationId
        every { processor.process(notificationId) } returns NotificationOutcome.FULFILLED
    }

    // ─── Wiązanie treści ──────────────────────────────────────────────────────

    @Test
    fun `notyfikacja JSON jest wiazana w calosci`() {
        val captured = slot<Przelewy24Client.P24Notification>()
        stubHappyPath(captured)

        mockMvc.perform(
            post(url).contentType(MediaType.APPLICATION_JSON).content(jacksonObjectMapper().writeValueAsString(expected))
        )
            .andExpect(status().isOk)
            .andExpect(content().string("OK"))

        assertEquals(expected, captured.captured)
        verify(exactly = 1) { processor.record(expected) }
        verify(exactly = 1) { processor.process(notificationId) }
    }

    @Test
    fun `notyfikacja JSON z nieznanymi polami nadal jest przyjmowana`() {
        // P24 dokłada pola bez zapowiedzi — nieznane pole nie może zamienić płatności w 400.
        val captured = slot<Przelewy24Client.P24Notification>()
        stubHappyPath(captured)
        val json = jacksonObjectMapper().writeValueAsString(expected).dropLast(1) + ""","nowePole":"x"}"""

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk)

        assertEquals(expected, captured.captured)
    }

    @Test
    fun `notyfikacja formularzowa (x-www-form-urlencoded) jest wiazana w calosci do klasy danych`() {
        val captured = slot<Przelewy24Client.P24Notification>()
        stubHappyPath(captured)

        mockMvc.perform(
            post(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(formBody(expected))
        )
            .andExpect(status().isOk)
            .andExpect(content().string("OK"))

        // Każde pole, nie tylko „czy coś przyszło": podpis liczy się ze wszystkich.
        assertEquals(expected, captured.captured)
        verify(exactly = 1) { processor.record(expected) }
    }

    @Test
    fun `notyfikacja formularzowa jako parametry zadania (tak, jak je widzi kontener servletow)`() {
        val captured = slot<Przelewy24Client.P24Notification>()
        stubHappyPath(captured)

        mockMvc.perform(
            post(url).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("merchantId", "11111")
                .param("posId", "22222")
                .param("sessionId", "sess-1")
                .param("amount", "4900")
                .param("originAmount", "4900")
                .param("currency", "PLN")
                .param("orderId", "123456")
                .param("methodId", "25")
                .param("statement", "p24-A1-B2-C3")
                .param("sign", "abc123")
        ).andExpect(status().isOk)

        assertEquals(expected, captured.captured)
    }

    @Test
    fun `formularz z polskimi literami i nieznanym polem - statement dochodzi bez zmian`() {
        val captured = slot<Przelewy24Client.P24Notification>()
        stubHappyPath(captured)
        val polish = expected.copy(statement = "Zapłata/Łódź \"Pakiet\" & co=1")

        mockMvc.perform(
            post(url).contentType("application/x-www-form-urlencoded;charset=UTF-8")
                .content(formBody(polish, extra = mapOf("nowePole" to "x")))
        ).andExpect(status().isOk)

        assertEquals(polish, captured.captured)
    }

    @Test
    fun `formularz podpisany prawdziwym kluczem przechodzi prawdziwa weryfikacje podpisu`() {
        // Bez mocka klienta: notyfikacja podpisana tak, jak podpisuje ją P24, wysłana formularzem
        // musi przejść weryfikację podpisu — czyli wiązanie nie gubi ani nie zmienia żadnego
        // pola wchodzącego do podpisu (w tym polskich liter w `statement`).
        val realClient = Przelewy24Client(
            Przelewy24Properties(merchantId = 11111, posId = 22222, crc = "crc-secret", apiKey = "api-key")
        )
        val mvc = MockMvcBuilders.standaloneSetup(Przelewy24WebhookController(realClient, processor)).build()
        val unsigned = expected.copy(statement = "Opłata za pakiet/Łódź", sign = "")
        val signed = unsigned.copy(sign = realClient.notificationSign(unsigned))
        every { processor.record(any()) } returns notificationId
        every { processor.process(notificationId) } returns NotificationOutcome.FULFILLED

        mvc.perform(post(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(formBody(signed)))
            .andExpect(status().isOk)
            .andExpect(content().string("OK"))

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(jacksonObjectMapper().writeValueAsString(signed)))
            .andExpect(status().isOk)

        verify(exactly = 2) { processor.record(signed) }
    }

    // ─── Kody odpowiedzi ──────────────────────────────────────────────────────

    @Test
    fun `niepoprawny podpis - 400 i nic nie jest zapisywane`() {
        every { p24Client.isNotificationSignValid(any()) } returns false

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(jacksonObjectMapper().writeValueAsString(expected)))
            .andExpect(status().isBadRequest)
            .andExpect(content().string("invalid signature"))

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(formBody(expected)))
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { processor.record(any()) }
        verify(exactly = 0) { processor.process(any()) }
    }

    @Test
    fun `nieudany zapis - 500, zeby P24 ponowilo, i bez proby obslugi`() {
        every { p24Client.isNotificationSignValid(any()) } returns true
        every { processor.record(any()) } throws RuntimeException("baza leży")

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(jacksonObjectMapper().writeValueAsString(expected)))
            .andExpect(status().isInternalServerError)
            .andExpect(content().string("storage error"))

        verify(exactly = 0) { processor.process(any()) }
    }

    @Test
    fun `blad obslugi po zapisie - 200, ponowienie jest nasze`() {
        every { p24Client.isNotificationSignValid(any()) } returns true
        every { processor.record(any()) } returns notificationId
        every { processor.process(notificationId) } throws RuntimeException("P24 verify timeout")

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(jacksonObjectMapper().writeValueAsString(expected)))
            .andExpect(status().isOk)
            .andExpect(content().string("OK"))

        verify(exactly = 1) { processor.record(expected) }
    }

    @Test
    fun `wynik obslugi inny niz realizacja nadal konczy sie 200`() {
        // Odrzucona kwota, duplikat, brak zamówienia — notyfikacja jest zapisana, decyzję
        // podjęliśmy; ponowienie P24 niczego by nie zmieniło.
        every { p24Client.isNotificationSignValid(any()) } returns true
        every { processor.record(any()) } returns notificationId
        listOf(NotificationOutcome.REJECTED, NotificationOutcome.DUPLICATE, NotificationOutcome.RETRY_SCHEDULED).forEach { outcome ->
            every { processor.process(notificationId) } returns outcome
            mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(jacksonObjectMapper().writeValueAsString(expected)))
                .andExpect(status().isOk)
        }
    }
}
