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

    // ── Wiadomość przekazana (Fwd) ───────────────────────────────────────────
    //
    // Zgłoszenie z produkcji (23.09): lead „oklejenie Ford Transit L3H3" został bez
    // auta. Klientka przekazała swoje zapytanie i skasowała nagłówek przekazania.
    // Gmail zostawił całą treść w div.gmail_quote, cleaner wyciął go jako historię
    // i wersja czysta wyszła PUSTA — przy 1627 znakach w części tekstowej. Model
    // rozpoznający auto dostał sam napis „Klient:". Dane klientki zmienione.

    private val forwardedPlain = "Dzień dobry,\r\n\r\n" +
        "proszę przygotowanie wyceny na wykonanie usługi oklejenia samochodu Ford\r\n" +
        "Transit L3H3 (V363). Zależy nam na wykonaniu aplikacji folii na wybranych\r\n" +
        "elementach samochodu.\r\n\r\n" +
        "Wszystkie folie w kolorze czarnym matowym.\r\n\r\n" +
        "Pozdrawiam,\r\n\r\n" +
        "--\r\n" +
        "Anna Nowak\r\n\r\n" +
        "600100200\r\n"

    private val forwardedHtml = """
        <div dir="ltr"><div class="gmail_quote gmail_quote_container"><div dir="ltr">
        <div>Dzień dobry,</div><div><br></div>
        <div>proszę przygotowanie wyceny na wykonanie usługi oklejenia samochodu Ford Transit L3H3 (V363). Zależy nam na wykonaniu aplikacji folii na wybranych elementach samochodu.</div>
        <div><br></div><div>Wszystkie folie w kolorze czarnym matowym.</div>
        <div><br></div><div>Pozdrawiam,</div><div><br></div>
        <span class="gmail_signature_prefix">-- </span><br>
        <div dir="ltr" class="gmail_signature">Anna Nowak<div>600100200</div></div>
        </div></div></div>
    """.trimIndent()

    @Test
    fun `przekazanie ze skasowanym naglowkiem - tresc wraca z czesci tekstowej`() {
        assertEquals("", cleaner.clean(forwardedHtml, null), "warunek testu: sam HTML nie zostawia nic, jak na produkcji")

        val cleaned = cleaner.clean(forwardedHtml, forwardedPlain)

        assertTrue(cleaned.contains("Ford"), "zgubiono markę: <$cleaned>")
        assertTrue(cleaned.contains("Transit L3H3"), "zgubiono model: <$cleaned>")
        assertFalse(cleaned.contains("Anna Nowak"), "stopka nie jest treścią")
        assertFalse(cleaned.contains("600100200"))
        assertFalse(cleaned.contains('\r'))
    }

    /** Recepcja przesyła zapytanie klienta: dopisek z własną stopką, pod nim przekazanie. */
    private val forwardWithNoteHtml = """
        <div dir="ltr">Dzień dobry, przesyłam zapytanie klienta poniżej.<div><br></div>
        <span class="gmail_signature_prefix">-- </span><br><div dir="ltr" class="gmail_signature">Ewa, recepcja</div><br>
        <div class="gmail_quote gmail_quote_container"><div dir="ltr" class="gmail_attr">---------- Forwarded message ---------<br>Od: <strong class="gmail_sendername" dir="auto">Jan Kowalski</strong> <span dir="auto">&lt;jan.kowalski@example.com&gt;</span><br>Date: pon., 21 wrz 2026 o 10:00<br>Subject: Wycena oklejenia<br>To: &lt;biuro@example.com&gt;<br></div><br><br>
        <div dir="ltr"><div>Dzień dobry,</div><div>proszę o wycenę oklejenia Ford Transit L3H3 na czarny mat.</div><div><br></div><div>Pozdrawiam,</div><div>Jan Kowalski</div></div>
        </div></div>
    """.trimIndent()

    private val forwardWithNotePlain = """
        Dzień dobry, przesyłam zapytanie klienta poniżej.

        --
        Ewa, recepcja

        ---------- Forwarded message ---------
        Od: Jan Kowalski <jan.kowalski@example.com>
        Date: pon., 21 wrz 2026 o 10:00
        Subject: Wycena oklejenia
        To: <biuro@example.com>


        Dzień dobry,
        proszę o wycenę oklejenia Ford Transit L3H3 na czarny mat.

        Pozdrawiam,
        Jan Kowalski
    """.trimIndent()

    private fun assertForwardWithNote(cleaned: String) {
        assertTrue(cleaned.contains("przesyłam zapytanie klienta"), "zgubiono dopisek: <$cleaned>")
        assertTrue(cleaned.contains("Ford Transit L3H3"), "zgubiono przekazane zapytanie: <$cleaned>")
        assertFalse(cleaned.contains("Forwarded message"), "znacznik przekazania to nie treść: <$cleaned>")
        assertFalse(cleaned.contains("Subject:"), "nagłówek przekazania to nie treść: <$cleaned>")
        assertFalse(cleaned.contains("jan.kowalski@example.com"))
        assertFalse(cleaned.contains("Jan Kowalski"), "ani nagłówek, ani stopka przekazanej wiadomości: <$cleaned>")
        assertFalse(cleaned.contains("Ewa, recepcja"), "stopka pod dopiskiem nie jest treścią: <$cleaned>")
    }

    @Test
    fun `przekazanie z dopiskiem w html - zostaje dopisek i przekazane zapytanie`() {
        // Część tekstowa celowo pusta: HTML zostawia dopisek, więc zejście do niej
        // by nie zadziałało — przekazanie musi przeżyć w samym HTML-u.
        assertForwardWithNote(cleaner.clean(forwardWithNoteHtml, null))
    }

    @Test
    fun `przekazanie z dopiskiem w czesci tekstowej - znacznik nie ucina zapytania`() {
        assertForwardWithNote(cleaner.clean(null, forwardWithNotePlain))
    }

    @Test
    fun `odpowiedz na przekazana wiadomosc nie wciaga przekazania z cytatu`() {
        val html = """
            <div dir="ltr">Dziękuję, zapisuję na piątek.</div><br>
            <div class="gmail_quote gmail_quote_container"><div dir="ltr" class="gmail_attr">W dniu pon., 21 wrz 2026 o 12:00 Studio &lt;biuro@example.com&gt; napisał(a):<br></div>
            <blockquote class="gmail_quote" style="margin:0px 0px 0px 0.8ex;border-left:1px solid rgb(204,204,204);padding-left:1ex">
            <div dir="ltr">Zapraszamy w piątek.</div>
            <div class="gmail_quote"><div dir="ltr" class="gmail_attr">---------- Forwarded message ---------<br>Od: Jan Kowalski</div><div>proszę o wycenę oklejenia Ford Transit</div></div>
            </blockquote></div>
        """.trimIndent()
        val plain = """
            Dziękuję, zapisuję na piątek.

            W dniu pon., 21 wrz 2026 o 12:00 Studio <biuro@example.com> napisał(a):
            > Zapraszamy w piątek.
            >
            > ---------- Forwarded message ---------
            > Od: Jan Kowalski <jan.kowalski@example.com>
            > proszę o wycenę oklejenia Ford Transit
        """.trimIndent()

        assertEquals("Dziękuję, zapisuję na piątek.", cleaner.clean(html, plain))
        assertEquals("Dziękuję, zapisuję na piątek.", cleaner.clean(null, plain))
    }

    @Test
    fun `przekazanie pod znacznikiem cytatu jest historia, nawet bez znaku cytatu`() {
        val text = """
            Dziękuję.

            -----Original Message-----
            From: Studio
            ---------- Forwarded message ---------
            proszę o wycenę oklejenia Ford Transit
        """.trimIndent()

        assertEquals("Dziękuję.", cleaner.clean(null, text))
    }

    @Test
    fun `odpowiedz z outlooka na watek zaczety przekazaniem nie dokleja starego zapytania`() {
        // Outlook cytuje bez „>" i bez „napisał(a)", więc dla czyszczenia to zwykły
        // tekst. Przekazanie pod jego nagłówkiem jest historią — wynik nie może być
        // gorszy niż przed rozpoznawaniem przekazań.
        val text = """
            Dziękuję, termin pasuje.

            ________________________________
            Od: Studio <biuro@example.com>
            Wysłano: poniedziałek, 21 września 2026 12:00
            Temat: RE: Fwd: Wycena oklejenia

            Zapraszamy w piątek.

            ---------- Forwarded message ---------
            Od: Jan Kowalski <jan.kowalski@example.com>

            proszę o wycenę oklejenia Ford Transit
        """.trimIndent()

        val cleaned = cleaner.clean(null, text)

        assertTrue(cleaned.startsWith("Dziękuję, termin pasuje."), "zgubiono odpowiedź: <$cleaned>")
        assertFalse(cleaned.contains("Ford Transit"), "przekazanie z historii wróciło do treści: <$cleaned>")
    }

    @Test
    fun `czesc tekstowa nie zastepuje html-a, ktory cos zostawil`() {
        val cleaned = cleaner.clean(
            "<div>Nowa treść</div><div class=\"gmail_quote\">stara korespondencja</div>",
            "Nowa treść\n\nstara korespondencja bez znacznika cytatu"
        )

        assertEquals("Nowa treść", cleaned)
    }

    @Test
    fun `zejscie do czesci tekstowej nie przywraca samego cytatu`() {
        // Odpowiedź bez własnego słowa: HTML pusty, bo to sam cytat. Część tekstowa
        // też jest samym cytatem — pusto ma zostać pusto, a nie zamienić się w historię.
        val html = """
            <div class="gmail_quote"><div dir="ltr" class="gmail_attr">W dniu 21.09.2026 o 11:17 klient@example.com napisał(a):<br></div><blockquote class="gmail_quote">Dzień dobry, proszę o wycenę.</blockquote></div>
        """.trimIndent()
        val plain = "W dniu 21.09.2026 o 11:17 klient@example.com napisał(a):\n> Dzień dobry, proszę o wycenę."

        assertEquals("", cleaner.clean(html, plain))
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
