package pl.detailing.crm.ksef.fetch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import pl.detailing.crm.ksef.domain.PaymentForm
import java.io.ByteArrayInputStream
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Dane strony faktury (sprzedawca / nabywca) wyciągnięte z XML KSeF.
 */
data class KsefXmlParty(
    val nip: String? = null,
    val name: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val countryCode: String? = null
)

/**
 * Dane płatności z sekcji <Platnosc> faktury KSeF.
 *
 * @property paid true gdy faktura oznaczona jako zapłacona (<Zaplacono>1</Zaplacono>)
 */
data class KsefXmlPayment(
    val paymentForm: PaymentForm? = null,
    val dueDate: LocalDate? = null,
    val bankAccount: String? = null,
    val paid: Boolean = false
)

/**
 * Pojedyncza pozycja faktury z sekcji <FaWiersz>.
 *
 * Pola wg schematu FA(2):
 *  P_7 – nazwa towaru/usługi, P_8A – jednostka miary, P_8B – ilość,
 *  P_9A – cena jedn. netto, P_11 – wartość netto, P_11A – wartość brutto, P_12 – stawka VAT
 */
data class KsefXmlLine(
    val lineNumber: Int,
    val name: String?,
    val unit: String?,
    val quantity: Double?,
    val unitPriceNet: Double?,
    val netValue: Double?,
    val grossValue: Double?,
    val vatRate: String?
)

/**
 * Wynik parsowania pełnego XML faktury KSeF.
 */
data class KsefXmlData(
    val seller: KsefXmlParty = KsefXmlParty(),
    val buyer: KsefXmlParty = KsefXmlParty(),
    val payment: KsefXmlPayment = KsefXmlPayment(),
    val lines: List<KsefXmlLine> = emptyList()
) {
    val paymentForm: PaymentForm? get() = payment.paymentForm
    val sellerName: String? get() = seller.name
    val buyerName: String? get() = buyer.name

    companion object {
        val EMPTY = KsefXmlData()
    }
}

/**
 * Parsuje pełny XML faktury KSeF (format FA v2) i wyciąga dane potrzebne
 * do prezentacji faktury: strony, płatność i pozycje.
 *
 * Schemat MF FA(2): http://crd.gov.pl/wzor/2023/06/29/12648/
 *
 * Struktura XML:
 *  - Sprzedawca:      //Podmiot1/DaneIdentyfikacyjne/{NIP,Nazwa}, //Podmiot1/Adres/{KodKraju,AdresL1,AdresL2}
 *  - Nabywca:         //Podmiot2/DaneIdentyfikacyjne/{NIP,Nazwa}, //Podmiot2/Adres/{KodKraju,AdresL1,AdresL2}
 *  - Płatność:        //Platnosc/{FormaPlatnosci,Zaplacono}, //Platnosc/TerminPlatnosci/Termin,
 *                     //Platnosc/RachunekBankowy/NrRB
 *  - Pozycje:         //Fa/FaWiersz (pola P_7, P_8A, P_8B, P_9A, P_11, P_11A, P_12)
 *
 * Parser jest odporny na:
 *  - przestrzenie nazw (wyrażenia local-name() ignorują namespace)
 *  - brak poszczególnych pól (zwraca null dla brakujących wartości)
 *  - uszkodzone lub puste XML-e (zwraca [KsefXmlData.EMPTY])
 */
@Component
class KsefInvoiceXmlParser {

    private val log = LoggerFactory.getLogger(KsefInvoiceXmlParser::class.java)

    /**
     * Parsuje pełny XML faktury i wyciąga strony, dane płatności oraz pozycje.
     *
     * @param xmlBytes surowy XML z KSeF (wynik [KSeFClient.getInvoice])
     * @return [KsefXmlData] z dostępnymi polami (pola niedostępne mają null / pustą listę)
     */
    fun parseInvoiceData(xmlBytes: ByteArray): KsefXmlData {
        if (xmlBytes.isEmpty()) return KsefXmlData.EMPTY

        return try {
            val doc = parseXml(xmlBytes)
            val xpath = XPathFactory.newInstance().newXPath()

            KsefXmlData(
                seller  = parseParty(xpath, doc, "Podmiot1"),
                buyer   = parseParty(xpath, doc, "Podmiot2"),
                payment = parsePayment(xpath, doc),
                lines   = parseLines(xpath, doc)
            )
        } catch (e: Exception) {
            log.warn("Nie udało się sparsować XML faktury KSeF: {}", e.message)
            KsefXmlData.EMPTY
        }
    }

    /** Zachowana dla kompatybilności wstecznej – deleguje do [parseInvoiceData]. */
    fun parsePaymentForm(xmlBytes: ByteArray): PaymentForm? = parseInvoiceData(xmlBytes).paymentForm

    // ── Strony faktury ───────────────────────────────────────────────────────

    private fun parseParty(xpath: XPath, doc: Document, subjectTag: String): KsefXmlParty {
        fun field(vararg pathTags: String): String? =
            extractText(xpath, doc, localNamePath(subjectTag, *pathTags))

        return KsefXmlParty(
            nip          = field("DaneIdentyfikacyjne", "NIP"),
            name         = field("DaneIdentyfikacyjne", "Nazwa"),
            addressLine1 = field("Adres", "AdresL1"),
            addressLine2 = field("Adres", "AdresL2"),
            countryCode  = field("Adres", "KodKraju")
        )
    }

    // ── Płatność ─────────────────────────────────────────────────────────────

    private fun parsePayment(xpath: XPath, doc: Document): KsefXmlPayment {
        val paymentCode = extractText(xpath, doc, localNamePath("Platnosc", "FormaPlatnosci"))
        val paymentForm = PaymentForm.fromKsefCode(paymentCode).also { form ->
            if (paymentCode != null && form == null) {
                log.warn("Nieznany kod FormaPlatnosci w XML KSeF: '{}'", paymentCode)
            }
        }

        return KsefXmlPayment(
            paymentForm = paymentForm,
            dueDate     = extractText(xpath, doc, localNamePath("Platnosc", "TerminPlatnosci", "Termin"))
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            bankAccount = extractText(xpath, doc, localNamePath("Platnosc", "RachunekBankowy", "NrRB")),
            paid        = extractText(xpath, doc, localNamePath("Platnosc", "Zaplacono")) == "1"
        )
    }

    // ── Pozycje ──────────────────────────────────────────────────────────────

    private fun parseLines(xpath: XPath, doc: Document): List<KsefXmlLine> {
        val nodes = xpath.evaluate("//*[local-name()='FaWiersz']", doc, XPathConstants.NODESET) as NodeList

        return (0 until nodes.length).mapNotNull { i ->
            val row = nodes.item(i) as? Element ?: return@mapNotNull null
            KsefXmlLine(
                lineNumber   = childText(row, "NrWierszaFa")?.toIntOrNull() ?: (i + 1),
                name         = childText(row, "P_7"),
                unit         = childText(row, "P_8A"),
                quantity     = childText(row, "P_8B")?.toDoubleOrNull(),
                unitPriceNet = childText(row, "P_9A")?.toDoubleOrNull(),
                netValue     = childText(row, "P_11")?.toDoubleOrNull(),
                grossValue   = childText(row, "P_11A")?.toDoubleOrNull(),
                vatRate      = childText(row, "P_12")
            )
        }.sortedBy { it.lineNumber }
    }

    /** Tekst pierwszego bezpośredniego dziecka o podanej nazwie lokalnej (ignoruje namespace). */
    private fun childText(parent: Element, localName: String): String? {
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && node.localName == localName) {
                return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
            node = node.nextSibling
        }
        return null
    }

    // ── XML helpers ──────────────────────────────────────────────────────────

    private fun parseXml(xmlBytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Zabezpieczenie przed XXE (XML External Entity injection)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xmlBytes))
    }

    /** Buduje XPath niezależny od namespace: //*[local-name()='A']/*[local-name()='B']/... */
    private fun localNamePath(vararg tags: String): String =
        tags.joinToString(separator = "/", prefix = "//") { "*[local-name()='$it']" }

    private fun extractText(xpath: XPath, doc: Document, expression: String): String? =
        runCatching { (xpath.evaluate(expression, doc, XPathConstants.STRING) as String).trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
}
