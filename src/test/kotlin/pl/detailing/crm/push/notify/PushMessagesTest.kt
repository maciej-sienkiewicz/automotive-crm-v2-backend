package pl.detailing.crm.push.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.LeadSource
import java.time.Instant
import java.time.LocalDate

/**
 * Treść powiadomień: to, co widać na ekranie blokady. Dwie reguły są tu ważniejsze
 * od brzmienia: wariant bez danych osobowych naprawdę ich nie ma, i żadna treść nie
 * skleja faktów kropką środkową.
 */
class PushMessagesTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun all(message: PushMessages.Message) = listOfNotNull(message.masked, message.personal)

    @Test
    fun `rezerwacja - data w tytule, dzis i jutro slownie`() {
        // 08:00 UTC = 10:00 w Warszawie (czas letni)
        val tomorrow = PushMessages.reservationCreated(
            "a1", Instant.parse("2026-09-26T08:00:00Z"), false, "BMW X5, WA 12345", listOf("Korekta lakieru"), null, today
        )
        assertEquals("Nowa rezerwacja: jutro, 10:00", tomorrow.masked.title)
        assertEquals("BMW X5, WA 12345. Korekta lakieru.", tomorrow.masked.body)
        assertEquals("/calendar/rezerwacja/a1?start=2026-09-26T08:00:00Z", tomorrow.masked.url)

        val later = PushMessages.whenLabel(Instant.parse("2026-10-01T08:00:00Z"), false, today)
        assertEquals("czwartek 1 października, 10:00", later)
        assertEquals("dziś", PushMessages.whenLabel(Instant.parse("2026-09-25T08:00:00Z"), true, today))
    }

    @Test
    fun `rezerwacja - nazwisko klienta wylacznie w wariancie osobowym`() {
        val message = PushMessages.reservationCreated(
            "a1", Instant.parse("2026-09-26T08:00:00Z"), false, "BMW X5, WA 12345",
            listOf("Korekta", "Powłoka", "Pranie", "Polerowanie"), "Jan Kowalski", today
        )
        assertFalse(message.masked.body.contains("Kowalski"))
        assertEquals("Jan Kowalski, BMW X5, WA 12345. Korekta, Powłoka i 2 inne usługi.", message.personal!!.body)
    }

    @Test
    fun `przyjecie pojazdu - marka w tytule, tablica i numer wizyty w tresci`() {
        val message = PushMessages.vehicleCheckedIn("v1", "2026/0042", "Audi A6", "PO 1234A", "Anna Nowak")
        assertEquals("Przyjęto pojazd: Audi A6", message.masked.title)
        assertEquals("PO 1234A, wizyta nr 2026/0042.", message.masked.body)
        assertEquals("Anna Nowak, PO 1234A, wizyta nr 2026/0042.", message.personal!!.body)
        assertEquals("/visits/v1", message.masked.url)
    }

    @Test
    fun `wydanie pojazdu - kwota w tytule, klient tylko dla uprawnionych`() {
        val message = PushMessages.visitCompleted("v1", 190000, "BMW X5, WA 12345", "Jan Kowalski")
        assertTrue(message.masked.title.startsWith("Wizyta zakończona: 1"))
        assertTrue(message.masked.title.contains("900,00"))
        assertEquals("Pojazd wydany: BMW X5, WA 12345.", message.masked.body)
        assertEquals("Pojazd wydany: Jan Kowalski, BMW X5, WA 12345.", message.personal!!.body)
    }

    @Test
    fun `lead bez uprawnien do danych osobowych nie niesie nazwiska ani telefonu`() {
        val message = PushMessages.newLead("l1", "Jan Kowalski", "+48 600 100 200", LeadSource.PHONE)
        assertEquals("Nowy lead", message.masked.title)
        assertEquals("Telefon.", message.masked.body)
        assertEquals("Nowy lead: Jan Kowalski", message.personal!!.title)
        assertEquals("Telefon, +48 600 100 200.", message.personal!!.body)
        // Lead bez danych: jeden wariant, bez pustego „personal".
        assertNull(PushMessages.newLead("l2", null, " ", LeadSource.FORM).personal)
    }

    @Test
    fun `kampania w rejonie - debiutant, jedna firma i zbiorczo`() {
        val debut = PushMessages.areaCampaigns(listOf(PushMessages.AreaAdvertiser("p1", "Detailing Max", 3, true)), today)!!
        assertEquals("Nowa firma reklamuje się w Twoim rejonie", debut.title)
        assertEquals("Detailing Max: 3 nowe reklamy na Facebooku i Instagramie.", debut.body)
        assertEquals("area-campaign-p1", debut.tag)

        val many = PushMessages.areaCampaigns(
            listOf(
                PushMessages.AreaAdvertiser("p1", "A", 1, false),
                PushMessages.AreaAdvertiser("p2", "B", 5, false),
                PushMessages.AreaAdvertiser("p3", "C", 2, false),
                PushMessages.AreaAdvertiser("p4", "D", 1, true)
            ),
            today
        )!!
        assertEquals("Nowe kampanie w Twoim rejonie: 4", many.title)
        assertEquals("B, C i 2 inne firmy.", many.body)
        assertNull(PushMessages.areaCampaigns(emptyList(), today))
    }

    @Test
    fun `zadna tresc nie skleja faktow kropka srodkowa`() {
        val messages = listOf(
            PushMessages.newLead("l", "Jan", "jan@x.pl", LeadSource.EMAIL),
            PushMessages.reservationCreated("a", Instant.now(), true, "BMW", listOf("A"), "Jan", today),
            PushMessages.vehicleCheckedIn("v", "1", "BMW", "WA 1", "Jan"),
            PushMessages.visitCompleted("v", 100, "BMW", "Jan")
        ).flatMap(::all)
        messages.forEach { payload ->
            assertFalse(payload.title.contains('·') || payload.body.contains('·'), payload.toString())
        }
    }

    /**
     * Chrome na Androidzie ukrywa za „Możliwy spam" powiadomienia, których treść
     * przypomina oszustwo - ocenia to lokalny model, bez listy zaufanych nadawców.
     * Studia zaczęły dostawać to ostrzeżenie, a „Właśnie zarobiłeś 1 900,00 zł" miało
     * dokładnie kształt oszustwa finansowego. Ten test nie odtworzy
     * modelu, ale pilnuje, żeby nie wróciły wzorce, które są w nim na pewno.
     */
    @Test
    fun `tresci nie brzmia jak oszustwo`() {
        val scammy = Regex("zarobiłeś|wygrałeś|wygrana|za darmo|darmow|pilne|natychmiast|kliknij|!", RegexOption.IGNORE_CASE)
        val payloads = listOf(
            PushMessages.newLead("l", "Jan", "jan@x.pl", LeadSource.EMAIL),
            PushMessages.reservationCreated("a", Instant.now(), true, "BMW", listOf("A"), "Jan", today),
            PushMessages.vehicleCheckedIn("v", "1", "BMW", "WA 1", "Jan"),
            PushMessages.visitCompleted("v", 190000, "BMW", "Jan")
        ).flatMap(::all) + listOfNotNull(
            PushMessages.areaCampaigns(listOf(PushMessages.AreaAdvertiser("p", "Firma", 2, true)), today)
        )
        payloads.forEach { payload ->
            assertFalse(scammy.containsMatchIn(payload.title + " " + payload.body), payload.toString())
            // Emoji (poza BMP i w zakresie symboli) - też sygnał spamu.
            assertFalse((payload.title + payload.body).any { Character.isSurrogate(it) }, payload.toString())
        }
    }

    @Test
    fun `odmiana liczebnikow`() {
        assertEquals("1 nowa reklama", PushMessages.plural(1, "nowa reklama", "nowe reklamy", "nowych reklam"))
        assertEquals("3 nowe reklamy", PushMessages.plural(3, "nowa reklama", "nowe reklamy", "nowych reklam"))
        assertEquals("12 nowych reklam", PushMessages.plural(12, "nowa reklama", "nowe reklamy", "nowych reklam"))
        assertEquals("22 nowe reklamy", PushMessages.plural(22, "nowa reklama", "nowe reklamy", "nowych reklam"))
    }

    @Test
    fun `opis pojazdu z tego, co wiadomo`() {
        assertEquals("BMW X5, WA 12345", PushMessages.vehicleLabel("BMW", "X5", "WA 12345"))
        assertEquals("WA 12345", PushMessages.vehicleLabel(" ", "", "WA 12345"))
        assertNull(PushMessages.vehicleLabel(null, null, null))
        assertNotNull(PushMessages.personName(null, null, "Firma Sp. z o.o."))
    }
}
