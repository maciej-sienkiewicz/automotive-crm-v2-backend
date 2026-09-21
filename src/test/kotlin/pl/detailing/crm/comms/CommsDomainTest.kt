package pl.detailing.crm.comms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.EmailTextCleaner
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import java.util.UUID

class EmailTextCleanerTest {

    private val cleaner = EmailTextCleaner()

    @Test
    fun `strips quoted history below polish reply marker`() {
        val text = """
            Dzień dobry, poproszę o wycenę powłoki.

            W dniu 12.08.2026 o 14:20 Studio Detailingu napisał(a):
            > Dziękujemy za wiadomość
        """.trimIndent()
        val cleaned = cleaner.clean(null, text)
        assertTrue(cleaned.contains("poproszę o wycenę"))
        assertFalse(cleaned.contains("Dziękujemy za wiadomość"))
    }

    @Test
    fun `drops gmail quote containers from html`() {
        val html = """
            <div>Nowa treść zapytania</div>
            <div class="gmail_quote">stara korespondencja</div>
        """.trimIndent()
        val cleaned = cleaner.clean(html, null)
        assertTrue(cleaned.contains("Nowa treść zapytania"))
        assertFalse(cleaned.contains("stara korespondencja"))
    }

    // ── Odpowiedź napisana POD cytatem ───────────────────────────────────────
    //
    // Zgłoszenie z produkcji: oś czasu leada pokazywała przy każdej wiadomości całą
    // poprzednią rozmowę. Nie dlatego, że czyszczenia nie było - tylko dlatego, że
    // zakładało odpowiedź NAD cytatem. Thunderbird, Roundcube i większość webmaili
    // piszą odwrotnie, więc znacznik "W dniu ... napisał(a):" stał w pierwszej
    // linijce, pętla przerywała od razu i oddawała pustkę.

    /** Dosłowne ciało wiadomości ze skrzynki biuro@carslab.pl z 21 września. */
    private val bottomPosted = """
        W dniu 2026-09-21 11:17, franaszekpiotr98@gmail.com napisał(a):
        > Dzień dobry,
        >
        > chciałbym zapytać o możliwość wykonania renowacji przednich lamp
        > pojazd Volkswagen Passat B7.
        >
        > Z góry dziękuję za informację.
        >
        > Pozdrawiam,
        > Piotr Franaszek

        Dzień dobry,

        Usługa renowacji reflektorów trwa u nas kilka godzin.
        Koszt usługi to 500,00 zł brutto / para reflektorów.

        Pozdrawiam
        Mikołaj Błaszczak
        CarsLab
    """.trimIndent()

    @Test
    fun `odpowiedz napisana pod cytatem nie znika`() {
        val cleaned = cleaner.clean(null, bottomPosted)

        assertTrue(cleaned.contains("Koszt usługi to 500,00 zł brutto"), "zgubiono treść odpowiedzi: <$cleaned>")
        assertTrue(cleaned.isNotBlank())
    }

    @Test
    fun `cytat nie wchodzi do wyniku, choc stal nad odpowiedzia`() {
        val cleaned = cleaner.clean(null, bottomPosted)

        assertFalse(cleaned.contains("Piotr Franaszek"))
        assertFalse(cleaned.contains("Volkswagen Passat B7"))
        assertFalse(cleaned.contains("W dniu 2026-09-21"))
        assertFalse(cleaned.contains(">"))
    }

    @Test
    fun `stopka pod odpowiedzia ucieta, stopka w cytacie nie konczy zbierania`() {
        // „Pozdrawiam" stoi i w cytacie, i pod odpowiedzią. To pierwsze nie może
        // zamykać zbierania, zanim cokolwiek się zaczęło - inaczej wynik jest pusty.
        val cleaned = cleaner.clean(null, bottomPosted)

        assertTrue(cleaned.contains("Dzień dobry"))
        assertFalse(cleaned.contains("Mikołaj Błaszczak"))
    }

    @Test
    fun `zagniezdzony cytat tez wylatuje w calosci`() {
        val text = """
            W dniu 2026-09-21 12:27, klient@example.com napisał(a):
            > Dziękuję, prosiłbym o zapisanie na piątek.
            >> Wiadomość napisana przez biuro@carslab.pl:
            >>> Dzień dobry, koszt to 500 zł.

            Oczywiście, wizyta wpisana.
        """.trimIndent()

        val cleaned = cleaner.clean(null, text)

        assertEquals("Oczywiście, wizyta wpisana.", cleaned)
    }

    @Test
    fun `top-posting dziala jak dotad - drugi przebieg go nie rusza`() {
        // Kolejność przebiegów nie jest dowolna: przy top-postingu przebieg „pod
        // cytatem" wciągnąłby ogon cudzej wiadomości, który nie jest oznaczony „>".
        val text = """
            Poproszę o wycenę powłoki.

            W dniu 12.08.2026 o 14:20 Studio napisał(a):
            > Dziękujemy za wiadomość
            Stopka cudzej wiadomości bez znaku cytatu
        """.trimIndent()

        val cleaned = cleaner.clean(null, text)

        assertEquals("Poproszę o wycenę powłoki.", cleaned)
    }

    @Test
    fun `wiadomosc zlozona wylacznie z cytatu oddaje pustke, a nie cytat`() {
        val text = """
            W dniu 2026-09-21 11:17, klient@example.com napisał(a):
            > Dzień dobry, proszę o wycenę.
        """.trimIndent()

        assertEquals("", cleaner.clean(null, text))
    }

    // ── Rozpoznanie niezależne od języka ─────────────────────────────────────
    //
    // Lista fraz zna tyle języków, ile jej wpisano, i każdy klient spoza tej listy
    // zostawia swoją zapowiedź cytatu w treści jak przypadkowe zdanie. Dlatego obok
    // fraz stoi reguła kształtu: krótka linia z dwukropkiem, a zaraz pod nią cytat.
    // Te testy nie wymieniają ani jednego słowa z listy.

    private fun quoted(intro: String, tail: String = "Dziękuję, czekam na wycenę.") = """
        $intro
        > Dzień dobry, w załączeniu wycena renowacji lamp.
        > Pozdrawiam, Studio

        $tail
    """.trimIndent()

    @Test
    fun `niemiecka zapowiedz cytatu, ktorej nie ma na zadnej liscie`() {
        val cleaned = cleaner.clean(null, quoted("Am 21.09.2026 um 11:17 schrieb Piotr Franaszek:"))

        assertEquals("Dziękuję, czekam na wycenę.", cleaned)
    }

    @Test
    fun `francuska zapowiedz cytatu ze spacja przed dwukropkiem`() {
        val cleaned = cleaner.clean(null, quoted("Le 21/09/2026 à 11:17, Piotr Franaszek a écrit :"))

        assertEquals("Dziękuję, czekam na wycenę.", cleaned)
    }

    @Test
    fun `zapowiedz cytatu nad top-postingiem tez dziala bez znajomosci jezyka`() {
        val text = """
            Poproszę o termin na przyszły tydzień.

            Am 21.09.2026 um 11:17 schrieb Studio:
            > Dzień dobry, w załączeniu wycena renowacji lamp.
        """.trimIndent()

        assertEquals("Poproszę o termin na przyszły tydzień.", cleaner.clean(null, text))
    }

    @Test
    fun `zwykle zdanie z dwukropkiem zostaje trescia`() {
        // Reguła kształtu nie może zjadać treści. Chroni ją warunek „zaraz pod
        // spodem stoi cytat" - bez cytatu dwukropek jest po prostu dwukropkiem.
        val text = """
            Dzień dobry, proszę o wycenę na:
            renowację lamp oraz polerowanie maski.
        """.trimIndent()

        val cleaned = cleaner.clean(null, text)

        assertTrue(cleaned.contains("proszę o wycenę na:"), "zjedzono treść: <$cleaned>")
        assertTrue(cleaned.contains("renowację lamp"))
    }

    @Test
    fun `dlugi akapit zakonczony dwukropkiem nie jest zapowiedzia cytatu`() {
        val akapit = "Chciałbym zapytać o możliwość wykonania renowacji reflektorów w moim " +
            "samochodzie, ponieważ zależy mi na usunięciu zmatowienia i zarysowań, a także " +
            "na zabezpieczeniu powierzchni po wykonanej usłudze, więc proszę o wycenę obejmującą:"

        val cleaned = cleaner.clean(null, "$akapit\n> stary cytat\n")

        assertTrue(cleaned.contains("proszę o wycenę obejmującą:"), "zjedzono akapit: <$cleaned>")
    }

    @Test
    fun `snippet is single line and bounded`() {
        val snippet = cleaner.snippet(null, "linia1\nlinia2\n" + "x".repeat(500))
        assertFalse(snippet.contains('\n'))
        assertTrue(snippet.length <= 160)
    }
}

class EmailHtmlSanitizerTest {

    private val sanitizer = EmailHtmlSanitizer()

    @Test
    fun `removes scripts and event handlers`() {
        val out = sanitizer.sanitize(
            """<div onclick="steal()">Hej<script>alert(1)</script><a href="javascript:x()">tu</a></div>""",
            UUID.randomUUID(),
            emptyMap()
        )
        assertFalse(out.contains("script", ignoreCase = true))
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("javascript:"))
        assertTrue(out.contains("Hej"))
    }

    @Test
    fun `rewrites known cid images and drops unknown ones`() {
        val attachmentId = UUID.randomUUID()
        val out = sanitizer.sanitize(
            """<img src="cid:logo123"><img src="cid:missing999">""",
            UUID.randomUUID(),
            mapOf("logo123" to attachmentId)
        )
        assertTrue(out.contains("/api/v1/comms/attachments/$attachmentId/inline"))
        assertFalse(out.contains("missing999"))
    }

    @Test
    fun `keeps remote https images and hardens links`() {
        val out = sanitizer.sanitize(
            """<img src="https://example.com/x.png"><a href="https://example.com">link</a>""",
            UUID.randomUUID(),
            emptyMap()
        )
        assertTrue(out.contains("https://example.com/x.png"))
        assertTrue(out.contains("rel=\"noopener noreferrer\""))
        assertTrue(out.contains("target=\"_blank\""))
    }

    @Test
    fun `escapes plain text fallback`() {
        val out = sanitizer.textAsHtml("<b>nie html</b>\ndruga linia")
        assertFalse(out.contains("<b>"))
        assertTrue(out.contains("druga linia"))
    }
}
