package pl.detailing.crm.visit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The confirmation request is the single carrier of the "Wyślij Kartę Wizyty" decision.
 * A body without the flag (older clients, the empty body the endpoint accepts) must mean
 * "do not send" — the regression being guarded is a customer receiving the card twice,
 * once from the backend on confirmation and once from a second explicit call.
 */
class ConfirmVisitRequestTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `an empty body does not send the visit card`() {
        val req: ConfirmVisitRequest = mapper.readValue("{}")
        assertFalse(req.sendVisitCard)
        assertFalse(req.sendEmail)
        assertNull(req.emailOptions)
    }

    @Test
    fun `the flag is read from the body`() {
        assertTrue(mapper.readValue<ConfirmVisitRequest>("""{"sendVisitCard":true}""").sendVisitCard)
        assertFalse(mapper.readValue<ConfirmVisitRequest>("""{"sendVisitCard":false,"sendEmail":true}""").sendVisitCard)
    }

    @Test
    fun `the response carries the card outcome only when a send was requested`() {
        val without = mapper.readValue<Map<String, Any?>>(mapper.writeValueAsString(ConfirmVisitResponse("v1", "ok")))
        assertNull(without["visitCard"])

        val with = ConfirmVisitResponse("v1", "ok", ConfirmVisitCardResult(emailSent = false, smsSent = true, message = "Wysłano SMS"))
        val json = mapper.readValue<Map<String, Any?>>(mapper.writeValueAsString(with))
        @Suppress("UNCHECKED_CAST")
        val card = json["visitCard"] as Map<String, Any?>
        assertEquals(true, card["smsSent"])
        assertEquals(false, card["emailSent"])
        assertEquals("Wysłano SMS", card["message"])
    }
}
