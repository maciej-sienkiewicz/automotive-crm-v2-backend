package pl.detailing.crm.gus.adapter.bir.parser

import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

// ─── Raw DTOs (internal to the adapter layer) ─────────────────────────────────

data class GusSearchEntry(
    val regon: String,
    val nip: String,
    val statusNip: String?,
    val name: String,
    val city: String?,
    val postalCode: String?,
    val street: String?,
    val buildingNumber: String?,
    /** P = osoba prawna, F = osoba fizyczna, LP = lok. jedn. prawna, LF = lok. jedn. fizyczna */
    val entityType: String,
    /**
     * SilosID wskazuje rejestr: 1–3 = CEIDG, 4 = REGON os. fizycznych,
     * 5 = REGON spółki cywilne, 6 = REGON os. prawne
     */
    val silosId: Int,
    val activityEndDate: String?
)

data class GusFullReportEntry(
    val regon: String,
    val nip: String,
    val name: String,
    val shortName: String?,
    val krsNumber: String?,
    val postalCode: String?,
    val city: String?,
    val street: String?,
    val buildingNumber: String?,
    val apartmentNumber: String?,
    val phone: String?,
    val email: String?,
    val website: String?,
    val legalFormName: String?,
    val activityStartDate: String?,
    val activityEndDate: String?,
    val activitySuspendedDate: String?,
    /** Niepusty = GUS zwrócił błąd zamiast danych (np. ErrorCode=4 – nie znaleziono). */
    val errorCode: String?
)

// ─── Parser ───────────────────────────────────────────────────────────────────

/**
 * Parsuje surowy XML zwracany przez GUS BIR (pole wynikowe z koperty SOAP).
 * Obsługuje zarówno raport os. prawnej (prefix "praw_") jak i os. fizycznej ("fiz_").
 */
object GusXmlParser {
    private val log = LoggerFactory.getLogger(javaClass)
    private val docBuilderFactory = DocumentBuilderFactory.newInstance()

    /**
     * Parsuje wynik metody DaneSzukajPodmioty.
     * Jeden NIP może zwrócić >1 wpis (gdy podmiot ma kilka form działalności).
     */
    fun parseSearchResult(xml: String): List<GusSearchEntry> {
        if (xml.isBlank()) return emptyList()
        return try {
            val doc = docBuilderFactory.newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagName("dane")
            (0 until nodes.length).map { i ->
                val e = nodes.item(i) as Element
                GusSearchEntry(
                    regon          = e.text("Regon"),
                    nip            = e.text("Nip"),
                    statusNip      = e.text("StatusNip").takeIf { it.isNotBlank() },
                    name           = e.text("Nazwa"),
                    city           = e.text("Miejscowosc").takeIf { it.isNotBlank() },
                    postalCode     = e.text("KodPocztowy").takeIf { it.isNotBlank() },
                    street         = e.text("Ulica").takeIf { it.isNotBlank() },
                    buildingNumber = e.text("NrNieruchomosci").takeIf { it.isNotBlank() },
                    entityType     = e.text("Typ"),
                    silosId        = e.text("SilosID").toIntOrNull() ?: 0,
                    activityEndDate = e.text("DataZakonczeniaDzialalnosci").takeIf { it.isNotBlank() }
                )
            }
        } catch (ex: Exception) {
            log.error("Failed to parse GUS search result XML: {}", ex.message)
            emptyList()
        }
    }

    /**
     * Parsuje wynik metody DanePobierzPelnyRaport.
     * Zwraca null gdy XML jest pusty lub wystąpił błąd parsowania.
     * Obsługuje oba prefiksy pól: "praw_" (os. prawna) i "fiz_" (os. fizyczna).
     */
    fun parseFullReport(xml: String): GusFullReportEntry? {
        if (xml.isBlank()) return null
        return try {
            val doc = docBuilderFactory.newDocumentBuilder()
                .parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagName("dane")
            if (nodes.length == 0) return null
            val e = nodes.item(0) as Element

            val errorCode = e.text("ErrorCode").takeIf { it.isNotBlank() }

            // Wykryj prefiks na podstawie tego, który jest wypełniony
            val prefix = when {
                e.text("praw_regon9").isNotBlank() -> "praw_"
                e.text("fiz_regon9").isNotBlank()  -> "fiz_"
                else                                -> ""
            }

            // Dla os. fizycznych: imię i nazwisko zamiast nazwy firmy
            val name = if (prefix == "fiz_") {
                val nazwisko = e.text("fiz_nazwisko")
                val imie1    = e.text("fiz_imie1")
                val imie2    = e.text("fiz_imie2")
                buildString {
                    append(imie1)
                    if (imie2.isNotBlank()) append(" $imie2")
                    if (nazwisko.isNotBlank()) append(" $nazwisko")
                }.trim()
            } else {
                e.text("${prefix}nazwa")
            }

            GusFullReportEntry(
                regon               = e.text("${prefix}regon9").ifBlank { e.text("${prefix}regon14") },
                nip                 = e.text("${prefix}nip"),
                name                = name,
                shortName           = e.text("${prefix}nazwaSkrocona").takeIf { it.isNotBlank() },
                krsNumber           = e.text("${prefix}numerWRejestrzeEwidencji").takeIf { it.isNotBlank() },
                postalCode          = e.text("${prefix}adSiedzKodPocztowy").takeIf { it.isNotBlank() },
                city                = e.text("${prefix}adSiedzMiejscowosc_Nazwa").takeIf { it.isNotBlank() },
                street              = e.text("${prefix}adSiedzUlica_Nazwa").takeIf { it.isNotBlank() },
                buildingNumber      = e.text("${prefix}adSiedzNumerNieruchomosci").takeIf { it.isNotBlank() },
                apartmentNumber     = e.text("${prefix}adSiedzNumerLokalu").takeIf { it.isNotBlank() },
                phone               = e.text("${prefix}numerTelefonu").takeIf { it.isNotBlank() },
                email               = e.text("${prefix}adresEmail").takeIf { it.isNotBlank() },
                website             = e.text("${prefix}adresStronyinternetowej").takeIf { it.isNotBlank() },
                legalFormName       = (e.text("${prefix}szczegolnaFormaPrawna_Nazwa")
                    .takeIf { it.isNotBlank() }
                    ?: e.text("${prefix}podstawowaFormaPrawna_Nazwa").takeIf { it.isNotBlank() }),
                activityStartDate   = e.text("${prefix}dataRozpoczeciaDzialalnosci").takeIf { it.isNotBlank() },
                activityEndDate     = e.text("${prefix}dataZakonczeniaDzialalnosci").takeIf { it.isNotBlank() },
                activitySuspendedDate = e.text("${prefix}dataZawieszeniaDzialalnosci").takeIf { it.isNotBlank() },
                errorCode           = errorCode
            )
        } catch (ex: Exception) {
            log.error("Failed to parse GUS full report XML: {}", ex.message)
            null
        }
    }

    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent?.trim() ?: ""
}
