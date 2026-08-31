package pl.detailing.crm.customer.importing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Czyta plik `.vcf` na tyle, ile potrzeba do założenia kartoteki klienta.
 *
 * ## Dlaczego własny parser, a nie biblioteka
 *
 * Bierzemy z vCarda pięć rzeczy: nazwisko, imię, telefony, e-maile i firmę. Reszta —
 * zdjęcia, dzwonki, konta społecznościowe, pola `X-` producenta telefonu — jest dla CRM-a
 * bezużyteczna, a bywa ogromna (`PHOTO` w base64 potrafi ważyć więcej niż cała reszta
 * pliku). Pełna biblioteka wciągnęłaby to wszystko do pamięci, żebyśmy natychmiast to
 * wyrzucili.
 *
 * ## Czego to NIE robi
 *
 * Nie jest to zgodna z RFC implementacja vCarda i nie należy jej za taką uważać.
 * Nie obsługuje `AGENT`, zagnieżdżonych vCardów, `VERSION:4.0`-owych parametrów
 * `PREF=`, ani kodowań innych niż UTF-8. Wszystko, czego nie rozumie, po prostu pomija —
 * import ma wciągnąć kontakty, a nie odtworzyć wierną kopię wizytówki.
 *
 * ## Co obsługuje, bo bez tego telefony przysyłają śmieci
 *
 * - **vCard 2.1, 3.0 i 4.0** — trzy pokolenia formatu żyją obok siebie: iPhone eksportuje
 *   3.0, część Androidów nadal 2.1, a nowe narzędzia 4.0.
 * - **Składanie linii (folding)** — długa wartość jest łamana i kontynuowana w linii
 *   zaczynającej się od spacji lub tabulatora (RFC 6350 §3.2).
 * - **Quoted-printable** — zapis 2.1, w którym polskie znaki wyglądają jak `=C5=82`,
 *   a długie wartości łamią się znakiem `=` na końcu linii. Bez tego „Michał Wróbel"
 *   trafiłby do bazy jako „Micha=C5=82 Wr=C3=B3bel".
 * - **Grupy właściwości** (`item1.TEL:…`) — iOS grupuje pola i bez odcięcia przedrostka
 *   żaden telefon nie zostałby rozpoznany.
 */
@Component
class VCardParser {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun parse(content: String): List<ParsedContact> {
        val contacts = mutableListOf<ParsedContact>()
        var current: MutableList<Line>? = null

        unfold(content).forEach { line ->
            when {
                line.name.equals("BEGIN", true) && line.value.equals("VCARD", true) ->
                    current = mutableListOf()

                line.name.equals("END", true) && line.value.equals("VCARD", true) -> {
                    current?.let { properties ->
                        // Wizytówka bez czegokolwiek, po czym da się rozpoznać człowieka,
                        // nie jest kontaktem — jest wierszem do odrzucenia dalej w potoku.
                        runCatching { toContact(properties) }
                            .onFailure { logger.warn("Pominięto niezrozumiałą wizytówkę: ${it.message}") }
                            .getOrNull()
                            ?.let(contacts::add)
                    }
                    current = null
                }

                else -> current?.add(line)
            }
        }

        return contacts
    }

    // ── Rozbiór pojedynczej wizytówki ────────────────────────────────────────

    private fun toContact(properties: List<Line>): ParsedContact {
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        var displayName: String? = null
        var firstName: String? = null
        var lastName: String? = null
        var company: String? = null

        properties.forEach { line ->
            val value = decodeValue(line)
            if (value.isBlank()) return@forEach

            when (line.name.uppercase()) {
                "FN" -> displayName = value
                "N" -> {
                    // N:Nazwisko;Imię;drugie imię;tytuł;przyrostek
                    val parts = splitStructured(value)
                    lastName = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                    firstName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                }
                "TEL" -> phones += stripTelUri(value)
                "EMAIL" -> emails += value.trim()
                "ORG" -> company = splitStructured(value).firstOrNull()?.takeIf { it.isNotBlank() }
            }
        }

        // Gdy jest tylko FN (częste przy kontaktach zapisanych jednym ciągiem, np.
        // „Warsztat Kowalski"), rozbijamy je na imię i nazwisko po pierwszej spacji —
        // ale tylko wtedy, gdy N nic nie wniosło. Odwrotna kolejność nadpisywałaby
        // rzetelne dane zgadywanką.
        if (firstName == null && lastName == null && displayName != null) {
            val words = displayName!!.trim().split(Regex("\\s+"))
            if (words.size >= 2) {
                firstName = words.first()
                lastName = words.drop(1).joinToString(" ")
            } else {
                lastName = displayName
            }
        }

        return ParsedContact(
            firstName = firstName,
            lastName = lastName,
            displayName = displayName,
            phones = phones.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            emails = emails.filter { it.isNotBlank() }.distinct(),
            companyName = company
        )
    }

    // ── Warstwa tekstowa ─────────────────────────────────────────────────────

    private data class Line(val name: String, val params: List<String>, val value: String)

    /**
     * Zamienia surowy plik na listę właściwości, sklejając po drodze linie łamane.
     *
     * Dwa różne mechanizmy łamania, oba muszą zadziałać:
     *  - **folding** (2.1/3.0/4.0): kontynuacja zaczyna się spacją lub tabulatorem,
     *  - **soft line break quoted-printable** (2.1): poprzednia linia kończy się `=`,
     *    a kontynuacja NIE ma wcięcia.
     */
    private fun unfold(content: String): List<Line> {
        val physical = content.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val logical = mutableListOf<String>()

        physical.forEach { raw ->
            val previous = logical.lastOrNull()
            when {
                previous != null && (raw.startsWith(" ") || raw.startsWith("\t")) ->
                    logical[logical.lastIndex] = previous + raw.substring(1)

                previous != null && previous.endsWith("=") && previous.contains("QUOTED-PRINTABLE", true) ->
                    logical[logical.lastIndex] = previous.dropLast(1) + raw.trimStart()

                else -> logical += raw
            }
        }

        return logical.mapNotNull(::toLine)
    }

    /** `item1.TEL;TYPE=CELL:+48 600 100 200` → nazwa, parametry, wartość. */
    private fun toLine(raw: String): Line? {
        if (raw.isBlank()) return null
        val colon = indexOfValueSeparator(raw)
        if (colon <= 0) return null

        val head = raw.substring(0, colon)
        val value = raw.substring(colon + 1)
        val segments = head.split(';')
        // Przedrostek grupy („item1.") należy do składni, nie do nazwy właściwości.
        val name = segments.first().substringAfterLast('.')

        return Line(name = name, params = segments.drop(1), value = value)
    }

    /**
     * Pierwszy dwukropek, który rozdziela nagłówek od wartości.
     *
     * Nie da się użyć `indexOf(':')` bez zastanowienia: parametr w cudzysłowie może
     * zawierać dwukropek (`TEL;TYPE="work:main":+48…`), a wtedy nazwa właściwości
     * urwałaby się w połowie.
     */
    private fun indexOfValueSeparator(raw: String): Int {
        var inQuotes = false
        raw.forEachIndexed { index, char ->
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ':' && !inQuotes -> return index
            }
        }
        return -1
    }

    private fun decodeValue(line: Line): String {
        val quotedPrintable = line.params.any { it.contains("QUOTED-PRINTABLE", true) }
        val decoded = if (quotedPrintable) decodeQuotedPrintable(line.value) else line.value
        return unescape(decoded)
    }

    /** `Micha=C5=82` → `Michał`. Bajty zbieramy do końca sekwencji i dekodujemy naraz. */
    private fun decodeQuotedPrintable(value: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '=' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    bytes.write(byte)
                    index += 3
                    continue
                }
            }
            bytes.write(char.code)
            index++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** vCard escapuje `\,` `\;` `\n` `\\` — w wartości mają być zwykłymi znakami. */
    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n', 'N' -> append(' ')
                    ',', ';', '\\' -> append(next)
                    else -> append(next)
                }
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }

    /** Rozbija wartość strukturalną po `;`, respektując `\;`. */
    private fun splitStructured(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '\\' && index + 1 < value.length -> {
                    current.append(value[index + 1]); index += 2
                }
                char == ';' -> {
                    parts += current.toString().trim(); current.clear(); index++
                }
                else -> {
                    current.append(char); index++
                }
            }
        }
        parts += current.toString().trim()
        return parts
    }

    /** vCard 4.0 zapisuje numer jako URI: `tel:+48600100200`. */
    private fun stripTelUri(value: String): String =
        value.removePrefix("tel:").removePrefix("TEL:").substringBefore(';')
}

/**
 * Kontakt odczytany z wizytówki — surowy, jeszcze bez normalizacji i bez oceny, czy
 * nadaje się na klienta. To ostatni moment, w którym dane wyglądają dokładnie tak, jak
 * przysłał je telefon.
 */
data class ParsedContact(
    val firstName: String?,
    val lastName: String?,
    val displayName: String?,
    val phones: List<String>,
    val emails: List<String>,
    val companyName: String?
)
