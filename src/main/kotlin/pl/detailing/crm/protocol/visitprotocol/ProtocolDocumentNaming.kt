package pl.detailing.crm.protocol.visitprotocol

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Nazwy dokumentów wizyty: `24-08-2026_Porsche_911_Wojcik_przyjecie`.
 *
 * Jedno miejsce dla protokołów i zgód, bo ta sama nazwa trafia do listy dokumentów
 * wizyty i do nazwy pobieranego pliku — a wcześniej każdy z tych dokumentów nazywał
 * się `PPP_{numer}_{wersja}` i w liście nie dało się ich od siebie odróżnić.
 */
object ProtocolDocumentNaming {

    /** Dzień-miesiąc-rok: tak nazywa dokumenty człowiek szukający ich w folderze. */
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun build(date: LocalDate, vararg parts: String?): String =
        (listOf(DATE_FORMAT.format(date)) + parts.filterNotNull())
            .map { slug(it) }
            .filter { it.isNotBlank() }
            .joinToString("_")

    /** ASCII bez spacji: nazwa dokumentu jest zarazem nazwą pliku do pobrania. */
    fun slug(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "")
            .replace("ł", "l").replace("Ł", "L")
            .replace("[^A-Za-z0-9-]+".toRegex(), "-")
            .trim('-')

    /** Nazwisko klienta z pełnej nazwy — ostatni człon, jak w nazwie pliku. */
    fun surnameOf(fullName: String?): String =
        fullName?.trim()?.split(" ")?.lastOrNull().orEmpty()
}
