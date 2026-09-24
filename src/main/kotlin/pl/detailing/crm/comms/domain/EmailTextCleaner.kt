package pl.detailing.crm.comms.domain

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        Regex("^-{2,}\\s*(Original Message|Wiadomość oryginalna|Forwarded message|Wiadomość przekazana|Przekazana wiadomość)", RegexOption.IGNORE_CASE),
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

    /**
     * Początek wiadomości PRZEKAZANEJ. Stoi też w [quoteMarkers], bo pod cytatem
     * (przekazanie, na które ktoś odpisał) nadal jest historią — treścią staje się
     * dopiero wtedy, gdy nad nim nie zaczął się żaden cytat. Patrz [forwardStart].
     */
    private val forwardMarkers = listOf(
        Regex("^-{2,}\\s*(Forwarded message|Wiadomość przekazana|Przekazana wiadomość)", RegexOption.IGNORE_CASE)
    )

    /** Linia nagłówka przekazanej wiadomości: „Od: …", „Subject: …" i reszta. */
    private val forwardHeaderLine = Regex(
        "^\\*?(Od|From|Data|Date|Wysłano|Sent|Temat|Subject|Do|To|DW|Cc|UDW|Bcc)\\s*:",
        RegexOption.IGNORE_CASE
    )

    /** Pola otwierające nagłówek cytowanej wiadomości Outlooka — patrz [isHistoryHeader]. */
    private val outlookSeparator = Regex("^_{10,}$")
    private val historyFromLine = Regex("^\\*?(Od|From)\\s*:", RegexOption.IGNORE_CASE)
    private val historySentLine = Regex("^\\*?(Wysłano|Sent|Data|Date)\\s*:", RegexOption.IGNORE_CASE)

    /**
     * Najpierw HTML, a gdy z niego nic nie zostaje — część tekstowa.
     *
     * Pusty wynik z HTML-a nie znaczy „wiadomość jest pusta", tylko „wszystko, co w niej
     * było, siedziało w kontenerze cytatu". Zgłoszenie z produkcji: klientka przekazała
     * (Fwd) swoje zapytanie i skasowała nagłówek przekazania. Gmail zostawił treść
     * w `div.gmail_quote`, ten wyleciał w całości i wiadomość z „Ford Transit L3H3"
     * miała pustą wersję czystą — a rozpoznanie auta czytało właśnie ją i dostało
     * od modelu „brak marki". Część tekstowa tego samego maila była w porządku.
     */
    fun clean(html: String?, plainText: String?): String {
        val fromHtml = html?.takeIf { it.isNotBlank() }?.let { finish(htmlToText(it)) }.orEmpty()
        if (fromHtml.isNotEmpty()) return fromHtml
        return plainText?.takeIf { it.isNotBlank() }?.let { finish(it) }.orEmpty()
    }

    fun snippet(html: String?, plainText: String?, maxLength: Int = 160): String =
        clean(html, plainText).replace(Regex("\\s+"), " ").take(maxLength)

    private fun finish(text: String): String = stripQuotedHistory(text).take(MAX_CLEAN_LENGTH).trim()

    private fun htmlToText(html: String): String {
        val document = Jsoup.parse(html)
        document.select("style, script, head").remove()
        // Quoted-history containers used by the major clients.
        document.select(
            "div.gmail_quote, blockquote, div#divRplyFwdMsg, div.moz-cite-prefix, div.yahoo_quoted"
        ).filterNot { isForwardContainer(it) }.forEach { it.remove() }
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
     * Czy ten kontener niesie wiadomość PRZEKAZANĄ, a nie cytat odpowiedzi.
     *
     * Gmail używa `div.gmail_quote` do dwóch różnych rzeczy: przy odpowiedzi to
     * historia rozmowy, przy przekazaniu — treść, dla której ktoś w ogóle pisze.
     * Recepcja przesyłająca zapytanie klienta dopisuje „przesyłam poniżej", a samo
     * zapytanie (auto, zakres, termin) stoi w tym kontenerze. Wycięcie go zostawiało
     * z leada sam dopisek.
     *
     * Rozpoznajemy wyłącznie po nagłówku, który Gmail wstawia w `div.gmail_attr`
     * („---------- Forwarded message ---------"). Kształt kontenera nie wystarczy:
     * gdy ktoś nagłówek skasuje, przekazanie niczym nie różni się od cytatu, a wtedy
     * lepiej stracić treść (wraca przez część tekstową w [clean]) niż wciągnąć do
     * wyniku historię cudzej rozmowy. `blockquote` nigdy nie jest przekazaniem —
     * w nim leży cytat także wtedy, gdy cytowana wiadomość sama była przekazaniem.
     */
    private fun isForwardContainer(element: Element): Boolean {
        if (!element.hasClass("gmail_quote") || element.tagName() != "div") return false
        val header = element.children().firstOrNull { it.hasClass("gmail_attr") }?.text()?.trim() ?: return false
        return forwardMarkers.any { it.containsMatchIn(header) }
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
     *
     * Przekazanie idzie osobną drogą: dopisek nad nim i przekazana wiadomość to DWIE
     * treści, każda z własną stopką. Czyścimy je osobno i sklejamy — inaczej „Pozdrawiam"
     * pod dopiskiem albo sam znacznik przekazania ucinałyby zapytanie klienta.
     */
    private fun stripQuotedHistory(text: String): String =
        stripQuotedHistory(text.replace("\r\n", "\n").split('\n'))

    private fun stripQuotedHistory(lines: List<String>): String {
        val forwardAt = forwardStart(lines)
        if (forwardAt != null) {
            val note = stripQuotedHistory(lines.subList(0, forwardAt))
            val forwarded = stripQuotedHistory(withoutForwardHeader(lines.subList(forwardAt + 1, lines.size)))
            return listOf(note, forwarded).filter { it.isNotBlank() }.joinToString("\n\n")
        }
        val aboveQuote = collectAboveQuote(lines)
        if (aboveQuote.isNotBlank()) return aboveQuote
        return collectBelowQuote(lines)
    }

    /**
     * Indeks znacznika przekazania, o ile stoi PRZED jakimkolwiek cytatem.
     *
     * Przekazanie pod „W dniu … napisał(a):" to fragment cytowanej historii — ktoś
     * odpisał na przekazaną wiadomość — i musi zniknąć razem z nią. Stąd przerwanie
     * na pierwszym znaczniku cytatu, a nie szukanie przekazania w całym tekście.
     */
    private fun forwardStart(lines: List<String>): Int? {
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(">")) continue
            if (forwardMarkers.any { it.containsMatchIn(trimmed) }) return index
            if (quoteMarkers.any { it.containsMatchIn(trimmed) } || isQuoteIntro(lines, index)) return null
            if (isHistoryHeader(lines, index)) return null
        }
        return null
    }

    /**
     * Nagłówek cytowanej wiadomości w stylu Outlooka — bez „>" i bez „napisał(a)":
     * kreska z podkreślników albo „Od: …" z „Wysłano: …" tuż pod spodem.
     *
     * Dla zwykłego czyszczenia to tekst jak każdy inny (zwykle ucina go wcześniej
     * stopka). Ale przekazanie stojące POD takim nagłówkiem jest historią wątku,
     * który zaczął się od przekazania — bez tego zatrzymania [forwardStart] doszedłby
     * do niego i dokleił stare zapytanie do nowej odpowiedzi.
     */
    private fun isHistoryHeader(lines: List<String>, index: Int): Boolean {
        val line = lines[index].trim()
        if (outlookSeparator.matches(line)) return true
        if (!historyFromLine.containsMatchIn(line)) return false
        val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }?.trim() ?: return false
        return historySentLine.containsMatchIn(next)
    }

    /**
     * Przekazana wiadomość bez swojego nagłówka („Od:", „Date:", „Subject:", „To:").
     *
     * Puste linie wewnątrz nagłówka są przeskakiwane, bo z HTML-a Gmaila każde pole
     * wychodzi w osobnym wierszu przedzielonym pustym. Pierwsza linia, która nie jest
     * ani pusta, ani polem nagłówka, zaczyna treść.
     */
    private fun withoutForwardHeader(lines: List<String>): List<String> {
        val bodyStart = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !forwardHeaderLine.containsMatchIn(trimmed)
        }
        return if (bodyStart < 0) emptyList() else lines.subList(bodyStart, lines.size)
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
