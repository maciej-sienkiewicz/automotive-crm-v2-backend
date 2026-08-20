package pl.detailing.crm.leads

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.intake.FormPayloadReader
import pl.detailing.crm.leads.intake.LeadFieldMapper
import pl.detailing.crm.leads.intake.LeadFormField

/**
 * Ten test jest umową z rzeczywistością, a nie z naszym API.
 *
 * Każdy przypadek to kształt, który naprawdę wysyła jedna z wtyczek używanych przez
 * studia: Contact Form 7 kluczuje nazwami tagów, Elementor etykietami ze strony
 * i domyślnie form-encoded, Tally zagnieżdża tablicę {label, value} pod data.fields.
 * Jeśli któryś z nich przestanie się mapować, integracja przestaje działać u klienta,
 * a my dowiemy się o tym z reklamacji — chyba że stąd.
 */
class LeadFormMappingTest {

    private val objectMapper = ObjectMapper()
    private val reader = FormPayloadReader(objectMapper)
    private val mapper = LeadFieldMapper(objectMapper)

    private fun map(body: String, contentType: String? = null, overrides: String? = null) =
        mapper.map(reader.read(body, contentType), overrides)

    @Test
    fun `Contact Form 7 — nazwy tagow`() {
        val form = map(
            """{"your-name":"Jan Kowalski","your-email":"jan@example.com",
               "your-phone":"600100200","your-message":"Poproszę o wycenę ceramiki"}""",
            contentType = "application/json"
        )

        assertEquals("Jan Kowalski", form[LeadFormField.NAME])
        assertEquals("jan@example.com", form[LeadFormField.EMAIL])
        assertEquals("600100200", form[LeadFormField.PHONE])
        assertEquals("Poproszę o wycenę ceramiki", form[LeadFormField.MESSAGE])
    }

    @Test
    fun `Elementor — etykiety po polsku, cialo form-encoded`() {
        val form = map(
            "Imi%C4%99+i+nazwisko=Anna+Nowak&Telefon=%2B48+601+202+303" +
                "&Adres+e-mail=anna%40example.com&Marka+pojazdu=BMW&Model=X5" +
                "&Wiadomo%C5%9B%C4%87=Przegl%C4%85d+folii",
            contentType = "application/x-www-form-urlencoded"
        )

        assertEquals("Anna Nowak", form[LeadFormField.NAME])
        assertEquals("+48 601 202 303", form[LeadFormField.PHONE])
        assertEquals("anna@example.com", form[LeadFormField.EMAIL])
        assertEquals("BMW", form[LeadFormField.VEHICLE_BRAND])
        assertEquals("X5", form[LeadFormField.VEHICLE_MODEL])
        assertEquals("Przegląd folii", form[LeadFormField.MESSAGE])
    }

    @Test
    fun `Tally — tablica label-value pod data fields`() {
        val form = map(
            """{"eventType":"FORM_RESPONSE","createdAt":"2026-08-19T15:00:21.889Z",
                "data":{"responseId":"2wgx4n","formName":"Wycena",
                "fields":[
                  {"key":"question_1","label":"E-mail","type":"INPUT_EMAIL","value":"kasia@example.com"},
                  {"key":"question_2","label":"Telefon","type":"INPUT_PHONE_NUMBER","value":"602303404"},
                  {"key":"question_3","label":"Zakres usług","type":"CHECKBOXES","value":["Powłoka ceramiczna","Korekta lakieru"]},
                  {"key":"question_4","label":"utm_campaign","type":"HIDDEN_FIELDS","value":"jesien"}
                ]}}""",
            contentType = "application/json"
        )

        assertEquals("kasia@example.com", form[LeadFormField.EMAIL])
        assertEquals("602303404", form[LeadFormField.PHONE])
        assertEquals("Powłoka ceramiczna, Korekta lakieru", form[LeadFormField.SERVICE])
        // UTM-y to nie treść zapytania — nie mają czego szukać w wiadomości.
        assertTrue(form.leftovers.none { it.label.contains("utm", ignoreCase = true) })
    }

    @Test
    fun `pola nierozpoznane zostaja, zeby trafic do tresci zapytania`() {
        val form = map(
            """{"email":"jan@example.com","Skąd o nas wiesz":"Instagram",
                "Preferowany termin":"przyszły tydzień"}""",
            contentType = "application/json"
        )

        val labels = form.leftovers.map { it.label }
        assertTrue(labels.contains("Skąd o nas wiesz"))
        assertTrue(labels.contains("Preferowany termin"))
    }

    @Test
    fun `nadpisanie z webhooka wygrywa ze slownikiem`() {
        val form = map(
            """{"numer-do-kontaktu-zwrotnego":"605505505","email":"jan@example.com"}""",
            contentType = "application/json",
            overrides = """{"phone":["numer-do-kontaktu-zwrotnego"]}"""
        )

        assertEquals("605505505", form[LeadFormField.PHONE])
    }

    @Test
    fun `zagniezdzone obiekty splaszczaja sie do ogona nazwy`() {
        val form = map(
            """{"payload":{"kontakt":{"email":"biuro@firma.pl","telefon":"221234567"}}}""",
            contentType = "application/json"
        )

        assertEquals("biuro@firma.pl", form[LeadFormField.EMAIL])
        assertEquals("221234567", form[LeadFormField.PHONE])
    }

    @Test
    fun `puste i smieciowe zgloszenie nie udaje, ze cos rozpoznalo`() {
        val form = map("""{"utm_source":"google","form_id":"7"}""", contentType = "application/json")

        assertNull(form[LeadFormField.EMAIL])
        assertNull(form[LeadFormField.PHONE])
        assertTrue(form.leftovers.isEmpty())
    }

    @Test
    fun `marka i model rozpoznaja sie mimo dopisku w etykiecie`() {
        val form = map(
            """{"Marka samochodu":"Mercedes","Model samochodu":"C 220","Rok produkcji":"2019","email":"a@b.pl"}""",
            contentType = "application/json"
        )

        assertEquals("Mercedes", form[LeadFormField.VEHICLE_BRAND])
        assertEquals("C 220", form[LeadFormField.VEHICLE_MODEL])
        assertEquals("2019", form[LeadFormField.VEHICLE_YEAR])
    }
}
