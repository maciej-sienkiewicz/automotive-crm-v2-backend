package pl.detailing.crm.ksef.fetch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.xml.sax.InputSource
import pl.detailing.crm.ksef.domain.PaymentForm
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Parsuje pełny XML faktury KSeF (format FA v2) i wyciąga wybrane pola.
 *
 * Schemat MF FA(2): http://crd.gov.pl/wzor/2023/06/29/12648/
 * Forma płatności jest w: //Platnosc/FormaPlatnosci lub //Fa/Platnosc/FormaPlatnosci
 *
 * Parser jest odporny na:
 *  - przestrzenie nazw (namespace-aware + namespace-ignorant fallback)
 *  - brak pola <FormaPlatnosci> (faktury bez określonej formy płatności)
 *  - uszkodzone lub puste XML-e (zwraca null zamiast rzucać wyjątek)
 */
@Component
class KsefInvoiceXmlParser {

    private val log = LoggerFactory.getLogger(KsefInvoiceXmlParser::class.java)

    /**
     * Wyciąga formę płatności z XML faktury.
     *
     * @param xmlBytes surowy XML z KSeF (wynik [KSeFClient.getInvoice])
     * @return [PaymentForm] lub null gdy brak pola lub nieznany kod
     */
    fun parsePaymentForm(xmlBytes: ByteArray): PaymentForm? {
        if (xmlBytes.isEmpty()) return null

        return try {
            val doc = parseXml(xmlBytes)
            val code = extractFormaPlatnosci(doc)
            PaymentForm.fromKsefCode(code).also { form ->
                if (code != null && form == null) {
                    log.warn("Nieznany kod FormaPlatnosci w XML KSeF: '{}'", code)
                }
            }
        } catch (e: Exception) {
            log.warn("Nie udało się sparsować XML faktury KSeF: {}", e.message)
            null
        }
    }

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

    /**
     * Próbuje wyciągnąć tekst <FormaPlatnosci> kilkoma XPath-ami.
     *
     * KSeF FA(2) może mieć tę samą strukturę pod różnymi ścieżkami w zależności
     * od wersji schematu, dlatego sprawdzamy kolejno:
     *   1. //Platnosc/FormaPlatnosci  – najczęstszy w FA v2
     *   2. //*[local-name()='FormaPlatnosci']  – fallback ignorujący namespace
     */
    private fun extractFormaPlatnosci(doc: Document): String? {
        val xpath = XPathFactory.newInstance().newXPath()

        val candidates = listOf(
            "//Platnosc/FormaPlatnosci",
            "//*[local-name()='FormaPlatnosci']"
        )

        for (expression in candidates) {
            val value = runCatching {
                (xpath.evaluate(expression, doc, XPathConstants.STRING) as String).trim()
            }.getOrNull()

            if (!value.isNullOrEmpty()) return value
        }

        return null
    }
}
