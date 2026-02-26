package pl.detailing.crm.ksef.fetch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import pl.detailing.crm.ksef.domain.PaymentForm
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Wynik parsowania pełnego XML faktury KSeF.
 */
data class KsefXmlData(
    val paymentForm: PaymentForm?,
    val sellerName: String?,
    val buyerName: String?
)

/**
 * Parsuje pełny XML faktury KSeF (format FA v2) i wyciąga wybrane pola.
 *
 * Schemat MF FA(2): http://crd.gov.pl/wzor/2023/06/29/12648/
 *
 * Struktura XML:
 *  - Forma płatności:  //Platnosc/FormaPlatnosci
 *  - Nazwa sprzedawcy: //Podmiot1/DaneIdentyfikacyjne/Nazwa
 *  - Nazwa nabywcy:    //Podmiot2/DaneIdentyfikacyjne/Nazwa
 *
 * Parser jest odporny na:
 *  - przestrzenie nazw (namespace-aware + namespace-ignorant fallback)
 *  - brak poszczególnych pól (zwraca null dla brakujących wartości)
 *  - uszkodzone lub puste XML-e (zwraca obiekt z samymi nullami)
 */
@Component
class KsefInvoiceXmlParser {

    private val log = LoggerFactory.getLogger(KsefInvoiceXmlParser::class.java)

    /**
     * Parsuje pełny XML faktury i wyciąga: formę płatności, nazwę sprzedawcy i nabywcy.
     *
     * @param xmlBytes surowy XML z KSeF (wynik [KSeFClient.getInvoice])
     * @return [KsefXmlData] z dostępnymi polami (pola niedostępne mają null)
     */
    fun parseInvoiceData(xmlBytes: ByteArray): KsefXmlData {
        if (xmlBytes.isEmpty()) return KsefXmlData(null, null, null)

        return try {
            val doc = parseXml(xmlBytes)
            val xpath = XPathFactory.newInstance().newXPath()

            val paymentCode = extractText(xpath, doc, listOf(
                "//Platnosc/FormaPlatnosci",
                "//*[local-name()='FormaPlatnosci']"
            ))
            val paymentForm = PaymentForm.fromKsefCode(paymentCode).also { form ->
                if (paymentCode != null && form == null) {
                    log.warn("Nieznany kod FormaPlatnosci w XML KSeF: '{}'", paymentCode)
                }
            }

            val sellerName = extractText(xpath, doc, listOf(
                "//Podmiot1/DaneIdentyfikacyjne/Nazwa",
                "//*[local-name()='Podmiot1']/*[local-name()='DaneIdentyfikacyjne']/*[local-name()='Nazwa']"
            ))

            val buyerName = extractText(xpath, doc, listOf(
                "//Podmiot2/DaneIdentyfikacyjne/Nazwa",
                "//*[local-name()='Podmiot2']/*[local-name()='DaneIdentyfikacyjne']/*[local-name()='Nazwa']"
            ))

            KsefXmlData(paymentForm, sellerName, buyerName)
        } catch (e: Exception) {
            log.warn("Nie udało się sparsować XML faktury KSeF: {}", e.message)
            KsefXmlData(null, null, null)
        }
    }

    /** Zachowana dla kompatybilności wstecznej – deleguje do [parseInvoiceData]. */
    fun parsePaymentForm(xmlBytes: ByteArray): PaymentForm? = parseInvoiceData(xmlBytes).paymentForm

    // ─────────────────────────────────────────────────────────────────────────

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

    private fun extractText(xpath: javax.xml.xpath.XPath, doc: Document, candidates: List<String>): String? {
        for (expression in candidates) {
            val value = runCatching {
                (xpath.evaluate(expression, doc, XPathConstants.STRING) as String).trim()
            }.getOrNull()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }
}
