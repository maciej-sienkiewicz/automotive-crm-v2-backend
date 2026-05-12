package pl.detailing.crm.gus.adapter.bir.soap

import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.xml.sax.InputSource
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Niskopoziomowy klient SOAP 1.2 dla GUS BIR API (ver. 1.1).
 * Odpowiada wyłącznie za:
 *  - serializację żądań do XML,
 *  - wysłanie ich przez HTTP,
 *  - ekstrakcję pola wynikowego ze zwróconej koperty SOAP.
 *
 * Nie zarządza sesją ani nie implementuje logiki biznesowej.
 */
class GusRawSoapClient(
    private val restTemplate: RestTemplate,
    private val endpointUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val docBuilderFactory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }

    // ─── Public operations ────────────────────────────────────────────────────

    fun login(apiKey: String): String {
        log.debug("GUS Zaloguj – initiating session")
        val response = callSoap(loginEnvelope(apiKey), GusActions.ZALOGUJ, sessionId = null)
        return extractText(response, "ZalogujResult")
            ?: throw GusServiceUnavailableException("GUS zwrócił pustą odpowiedź na Zaloguj – sprawdź klucz API")
    }

    fun logout(sessionId: String) {
        try {
            callSoap(logoutEnvelope(sessionId), GusActions.WYLOGUJ, sessionId)
            log.debug("GUS Wyloguj – session closed")
        } catch (ignored: Exception) {
            log.warn("GUS Wyloguj failed (best-effort, ignored): ${ignored.message}")
        }
    }

    fun searchByNip(nip: String, sessionId: String): String {
        log.debug("GUS DaneSzukajPodmioty – NIP: {}", nip)
        val response = callSoap(searchByNipEnvelope(nip), GusActions.DANE_SZUKAJ, sessionId)
        return extractText(response, "DaneSzukajPodmiotyResult") ?: ""
    }

    fun fetchFullReport(regon: String, reportName: String, sessionId: String): String {
        log.debug("GUS DanePobierzPelnyRaport – REGON: {}, raport: {}", regon, reportName)
        val response = callSoap(fullReportEnvelope(regon, reportName), GusActions.DANE_POBIERZ_PELNY, sessionId)
        return extractText(response, "DanePobierzPelnyRaportResult")
            ?: throw GusServiceUnavailableException("GUS zwrócił pustą odpowiedź na DanePobierzPelnyRaport")
    }

    /**
     * Wywołuje GetValue(StatusSesji) – metoda diagnostyczna z dokumentacji GUS BIR 1.1.
     * Zwraca "1" gdy sesja aktywna, "0" gdy wygasła.
     * Uwaga: GetValue używa innej przestrzeni nazw niż pozostałe metody (brak /PUBL/).
     */
    fun getSessionStatus(sessionId: String): String {
        log.debug("GUS GetValue – StatusSesji")
        return try {
            val response = callSoap(getValueEnvelope("StatusSesji"), GusActions.GET_VALUE, sessionId)
            extractText(response, "GetValueResult") ?: "0"
        } catch (ex: Exception) {
            log.warn("GUS GetValue failed (assuming session valid): {}", ex.message)
            "1"
        }
    }

    // ─── HTTP transport ───────────────────────────────────────────────────────

    private fun callSoap(envelope: String, action: String, sessionId: String?): String {
        val headers = HttpHeaders().apply {
            // MUST use raw set() – MediaType.parseMediaType() silently drops the quotes around
            // the action URI when serializing back to string, causing WCF to return 400 Bad Request.
            // Correct wire format: action="http://CIS/BIR/..."
            set(HttpHeaders.CONTENT_TYPE, "application/soap+xml; charset=UTF-8; action=\"$action\"")
            if (sessionId != null) set("sid", sessionId)
        }
        return try {
            restTemplate.postForObject(endpointUrl, HttpEntity(envelope, headers), String::class.java)
                ?: throw GusServiceUnavailableException("Brak odpowiedzi HTTP z serwera GUS")
        } catch (ex: RestClientException) {
            throw GusServiceUnavailableException("Błąd komunikacji z GUS (${ex.javaClass.simpleName}): ${ex.message}", ex)
        }
    }

    // ─── SOAP response parsing ────────────────────────────────────────────────

    private fun extractText(soapResponse: String, tagName: String): String? = try {
        // GUS WCF service sometimes prepends a UTF-8 BOM (﻿) which causes
        // DocumentBuilder to fail with "Content is not allowed in prolog."
        val cleaned = soapResponse.trimStart('﻿')
        val doc = docBuilderFactory.newDocumentBuilder()
            .parse(InputSource(StringReader(cleaned)))
        doc.getElementsByTagName(tagName).item(0)?.textContent?.trim()
    } catch (ex: Exception) {
        val preview = soapResponse.take(120).replace("\n", "\\n").replace("\r", "\\r")
        log.error("Failed to extract <{}> from SOAP response: {} | response_start='{}'", tagName, ex.message, preview)
        null
    }

    // ─── SOAP Envelope builders ───────────────────────────────────────────────

    // Koperty budowane indywidualnie – zgodnie ze strukturą z dokumentacji GUS BIR 1.1.
    // Używamy jawnych prefiksów (ns:, dat:, wsa:) zamiast domyślnego xmlns=,
    // żeby namespace każdego elementu był identyczny jak w dokumentacji.

    private fun loginEnvelope(apiKey: String) =
        """<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:ns="http://CIS/BIR/PUBL/2014/07">
  <soap:Header xmlns:wsa="http://www.w3.org/2005/08/addressing">
    <wsa:To>$endpointUrl</wsa:To>
    <wsa:Action>http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/Zaloguj</wsa:Action>
  </soap:Header>
  <soap:Body>
    <ns:Zaloguj>
      <ns:pKluczUzytkownika>${escapeXml(apiKey)}</ns:pKluczUzytkownika>
    </ns:Zaloguj>
  </soap:Body>
</soap:Envelope>"""

    private fun logoutEnvelope(sessionId: String) =
        """<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:ns="http://CIS/BIR/PUBL/2014/07">
  <soap:Header xmlns:wsa="http://www.w3.org/2005/08/addressing">
    <wsa:To>$endpointUrl</wsa:To>
    <wsa:Action>http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/Wyloguj</wsa:Action>
  </soap:Header>
  <soap:Body>
    <ns:Wyloguj>
      <ns:pIdentyfikatorSesji>${escapeXml(sessionId)}</ns:pIdentyfikatorSesji>
    </ns:Wyloguj>
  </soap:Body>
</soap:Envelope>"""

    private fun searchByNipEnvelope(nip: String) =
        // ns: = http://CIS/BIR/PUBL/2014/07 (BIR – element pParametryWyszukiwania)
        // dat: = http://CIS/BIR/PUBL/2014/07/DataContract (DataContract – pola wewnętrzne jak Nip)
        """<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:ns="http://CIS/BIR/PUBL/2014/07"
                        xmlns:dat="http://CIS/BIR/PUBL/2014/07/DataContract">
  <soap:Header xmlns:wsa="http://www.w3.org/2005/08/addressing">
    <wsa:To>$endpointUrl</wsa:To>
    <wsa:Action>http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/DaneSzukajPodmioty</wsa:Action>
  </soap:Header>
  <soap:Body>
    <ns:DaneSzukajPodmioty>
      <ns:pParametryWyszukiwania>
        <dat:Nip>${escapeXml(nip)}</dat:Nip>
      </ns:pParametryWyszukiwania>
    </ns:DaneSzukajPodmioty>
  </soap:Body>
</soap:Envelope>"""

    private fun fullReportEnvelope(regon: String, reportName: String) =
        """<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:ns="http://CIS/BIR/PUBL/2014/07">
  <soap:Header xmlns:wsa="http://www.w3.org/2005/08/addressing">
    <wsa:To>$endpointUrl</wsa:To>
    <wsa:Action>http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/DanePobierzPelnyRaport</wsa:Action>
  </soap:Header>
  <soap:Body>
    <ns:DanePobierzPelnyRaport>
      <ns:pRegon>${escapeXml(regon)}</ns:pRegon>
      <ns:pNazwaRaportu>${escapeXml(reportName)}</ns:pNazwaRaportu>
    </ns:DanePobierzPelnyRaport>
  </soap:Body>
</soap:Envelope>"""

    // GetValue używa innej przestrzeni nazw: http://CIS/BIR/2014/07 (brak /PUBL/)
    private fun getValueEnvelope(paramName: String) =
        """<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:ns="http://CIS/BIR/2014/07">
  <soap:Header xmlns:wsa="http://www.w3.org/2005/08/addressing">
    <wsa:To>$endpointUrl</wsa:To>
    <wsa:Action>http://CIS/BIR/2014/07/IUslugaBIR/GetValue</wsa:Action>
  </soap:Header>
  <soap:Body>
    <ns:GetValue>
      <ns:pNazwaParametru>${escapeXml(paramName)}</ns:pNazwaParametru>
    </ns:GetValue>
  </soap:Body>
</soap:Envelope>"""

    private fun escapeXml(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

object GusActions {
    const val ZALOGUJ            = "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/Zaloguj"
    const val WYLOGUJ            = "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/Wyloguj"
    const val DANE_SZUKAJ        = "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/DaneSzukajPodmioty"
    const val DANE_POBIERZ_PELNY = "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/DanePobierzPelnyRaport"
    // GetValue ma inny namespace (brak /PUBL/) – zgodnie z dokumentacją GUS BIR 1.1
    const val GET_VALUE          = "http://CIS/BIR/2014/07/IUslugaBIR/GetValue"
}

object GusReportNames {
    const val OS_PRAWNA             = "BIR11OsPrawna"
    const val OS_FIZYCZNA_OGOLNE    = "BIR11OsFizycznaDaneOgolne"
    const val JEDN_LOK_OS_PRAWNEJ   = "BIR11JednLokalnaOsPrawnej"
    const val JEDN_LOK_OS_FIZYCZNEJ = "BIR11JednLokalnaOsFizycznej"
}
