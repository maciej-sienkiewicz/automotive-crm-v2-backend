package pl.detailing.crm.comms.domain

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Service

/**
 * Turns a raw e-mail body into denoised plain text for snippets, insights and search.
 *
 * Quoted history is the reason threads get expensive: by the third message a client's
 * reply carries the whole conversation again. Removing it is deterministic work, so it
 * is done once at ingest and stored, rather than paid for on every render.
 */
@Service
class EmailTextCleaner {

    private val quoteMarkers = listOf(
        Regex("^W dniu .{0,160}napisa[łl](a)?\\s*(:|\\(a\\):)", RegexOption.IGNORE_CASE),
        Regex("^Dnia .{0,160}(napisa[łl](a)?|pisze)\\s*:", RegexOption.IGNORE_CASE),
        Regex("^On .{0,160}wrote:", RegexOption.IGNORE_CASE),
        Regex("^-{2,}\\s*(Original Message|Wiadomość oryginalna|Forwarded message|Wiadomość przekazana)", RegexOption.IGNORE_CASE),
        Regex("^(pon|wt|śr|sr|czw|pt|sob|niedz)\\.,? .{0,160}napisa[łl](a)?\\s*(:|\\(a\\):)", RegexOption.IGNORE_CASE)
    )

    private val signatureMarkers = listOf(
        Regex("^--\\s*$"),
        Regex(
            "^(Pozdrawiam|Z poważaniem|Z wyrazami szacunku|Serdecznie pozdrawiam|Best regards|Kind regards)[,.!]?\\s*$",
            RegexOption.IGNORE_CASE
        ),
        Regex("^(Wysłane z|Sent from) ", RegexOption.IGNORE_CASE)
    )

    fun clean(html: String?, plainText: String?): String {
        val text = when {
            !html.isNullOrBlank() -> htmlToText(html)
            !plainText.isNullOrBlank() -> plainText
            else -> return ""
        }
        return stripQuotedHistory(text).take(MAX_CLEAN_LENGTH).trim()
    }

    fun snippet(html: String?, plainText: String?, maxLength: Int = 160): String =
        clean(html, plainText).replace(Regex("\\s+"), " ").take(maxLength)

    private fun htmlToText(html: String): String {
        val document = Jsoup.parse(html)
        document.select("style, script, head").remove()
        // Quoted-history containers used by the major clients.
        document.select(
            "div.gmail_quote, blockquote, div#divRplyFwdMsg, div.moz-cite-prefix, div.yahoo_quoted"
        ).remove()
        document.select("[style~=(?i)display:\\s*none]").remove()
        document.select("br, p, div, li, tr").before(LINE_BREAK_TOKEN)

        val stripped = Jsoup.clean(
            document.html(),
            "",
            Safelist.none(),
            Document.OutputSettings().prettyPrint(false)
        )
        return Parser.unescapeEntities(stripped, false).replace(LINE_BREAK_TOKEN, "\n")
    }

    /**
     * Odpowiedź bez cytowanej historii — niezależnie od tego, GDZIE ją napisano.
     *
     * Są dwa zwyczaje i oba są w powszechnym użyciu:
     *
     *  - top-posting (Gmail, Outlook): odpowiedź NAD cytatem, cytat na końcu;
     *  - bottom-posting (Thunderbird, Roundcube, wiele webmaili): znacznik „W dniu …
     *    napisał(a):" w PIERWSZEJ linijce, pod nim cytat, a odpowiedź dopiero pod nim.
     *
     * Wcześniej obsłużony był wyłącznie pierwszy: „zbieraj do znacznika cytatu, potem
     * przerwij". Przy bottom-postingu znacznik stoi w linijce nr 1, więc pętla
     * przerywała od razu i zwracała PUSTY tekst. Wołający miał na to awaryjne zejście
     * do surowego ciała — i to ono pokazywało na osi czasu leada całą poprzednią
     * rozmowę razem z odpowiedzią, przy każdej wiadomości od nowa.
     *
     * Stąd dwa przebiegi. Najpierw zwyczajowy, top-postingowy; jeśli nie zostawia ani
     * jednego znaku, próbujemy drugiego: wyrzuć cytat i znaczniki, zatrzymaj to, co
     * zostało pod spodem. Kolejność nie jest dowolna — przy top-postingu drugi przebieg
     * wciągnąłby ogon cudzej wiadomości, który nie jest oznaczony „>".
     */
    private fun stripQuotedHistory(text: String): String {
        val lines = text.replace("\r\n", "\n").split('\n')
        val aboveQuote = collectAboveQuote(lines)
        if (aboveQuote.isNotBlank()) return aboveQuote
        return collectBelowQuote(lines)
    }

    /**
     * Czy ta linia zapowiada cytat — rozpoznane BEZ znajomości języka.
     *
     * Lista [quoteMarkers] wymienia frazy („W dniu … napisał(a):", „On … wrote:")
     * i przez to zna dokładnie tyle języków, ile jej wpisano. Klient piszący
     * „Am 21.09.2026 schrieb Piotr:" albo „Le 21/09/2026 a écrit :" nie pasuje do
     * żadnej z nich, a wtedy zapowiedź cytatu zostaje w treści jak przypadkowe zdanie.
     *
     * Tu decyduje KSZTAŁT, nie słowa: krótka linia zakończona dwukropkiem, nad którą
     * zaraz zaczyna się blok cytowany („>"). Ta konwencja należy do formatu poczty,
     * a nie do języka — trzyma się jej każdy klient, także taki, o którym nikt tu
     * nie pomyślał.
     *
     * Warunek „zaraz pod nią stoi cytat" jest tym, co chroni przed fałszywym trafieniem:
     * zwykłe zdanie kończące się dwukropkiem („Proszę o wycenę na:") zostaje treścią,
     * dopóki nie następuje po nim cytowanie.
     */
    private fun isQuoteIntro(lines: List<String>, index: Int): Boolean {
        val line = lines[index].trim()
        if (!line.endsWith(":") || line.length > MAX_INTRO_LENGTH) return false

        for (i in index + 1 until lines.size) {
            val next = lines[i].trim()
            if (next.isEmpty()) continue
            return next.startsWith(">")
        }
        return false
    }

    /** Top-posting: wszystko do pierwszego znacznika cytatu albo stopki. */
    private fun collectAboveQuote(lines: List<String>): String {
        val kept = mutableListOf<String>()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(">")) continue
            if (quoteMarkers.any { it.containsMatchIn(trimmed) }) break
            if (isQuoteIntro(lines, index)) break
            if (signatureMarkers.any { it.matches(trimmed) }) break
            kept.add(line.trimEnd())
        }
        return kept.join()
    }

    /**
     * Bottom-posting: cytat i jego zapowiedź wylatują, zostaje reszta.
     *
     * Stopka ucina dopiero PO pierwszej linijce treści. Inaczej „Pozdrawiam" stojące
     * w cytacie zamykałoby zbieranie, zanim cokolwiek się zaczęło — a w cytacie stoi
     * prawie zawsze, bo cytujemy czyjąś podpisaną wiadomość.
     */
    private fun collectBelowQuote(lines: List<String>): String {
        val kept = mutableListOf<String>()
        var started = false
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            // Każdy poziom zagnieżdżenia („>", „>>", „>>>") to wciąż cytat.
            if (trimmed.startsWith(">")) continue
            if (quoteMarkers.any { it.containsMatchIn(trimmed) }) continue
            if (isQuoteIntro(lines, index)) continue
            if (!started && trimmed.isEmpty()) continue
            if (started && signatureMarkers.any { it.matches(trimmed) }) break
            started = true
            kept.add(line.trimEnd())
        }
        return kept.join()
    }

    private fun List<String>.join(): String =
        joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    companion object {
        const val MAX_CLEAN_LENGTH = 4000

        /**
         * Najdłuższa linia, którą uznamy za zapowiedź cytatu.
         *
         * Zapowiedź to data, nazwisko i czasownik. Zmierzone na prawdziwych klientach:
         * 63 znaki (Thunderbird), 76 (niemiecki Outlook), 84 (Gmail z pełnym adresem
         * w nawiasach ostrych). 160 daje tym dwukrotny zapas i jest tą samą granicą,
         * którą dopuszczają wzorce frazowe wyżej (`.{0,160}`).
         *
         * Granica nie jest kosmetyczna: bez niej akapit zakończony dwukropkiem
         * („…proszę o wycenę obejmującą:") stojący nad cytatem przepada razem z nim.
         */
        private const val MAX_INTRO_LENGTH = 160
        private const val LINE_BREAK_TOKEN = "@@CRM_BR@@"
    }
}
