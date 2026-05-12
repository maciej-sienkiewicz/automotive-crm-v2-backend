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
        val doc = docBuilderFactory.newDocumentBuilder()
            .parse(InputSource(StringReader(soapResponse)))
        doc.getElementsByTagName(tagName).item(0)?.textContent?.trim()
    } catch (ex: Exception) {
        log.error("Failed to extract <{}> from SOAP response: {}", tagName, ex.message)
        null
    }

    // ─── SOAP Envelope builders ───────────────────────────────────────────────

    private fun loginEnvelope(apiKey: String) = soapEnvelope(
        action = GusActions.ZALOGUJ,
        body = """<Zaloguj xmlns="http://CIS/BIR/PUBL/2014/07">
                    <pKluczUzytkownika>${escapeXml(apiKey)}</pKluczUzytkownika>
                  </Zaloguj>"""
    )

    private fun logoutEnvelope(sessionId: String) = soapEnvelope(
        action = GusActions.WYLOGUJ,
        body = """<Wyloguj xmlns="http://CIS/BIR/PUBL/2014/07">
                    <pIdentyfikatorSesji>${escapeXml(sessionId)}</pIdentyfikatorSesji>
                  </Wyloguj>"""
    )

    private fun searchByNipEnvelope(nip: String) = soapEnvelope(
        action = GusActions.DANE_SZUKAJ,
        body = """<DaneSzukajPodmioty xmlns="http://CIS/BIR/PUBL/2014/07">
                    <pParametryWyszukiwania xmlns:i="http://www.w3.org/2001/XMLSchema-instance"
                                           xmlns="http://CIS/BIR/PUBL/2014/07/DataContract">
                      <Nip>${escapeXml(nip)}</Nip>
                    </pParametryWyszukiwania>
                  </DaneSzukajPodmioty>"""
    )

    private fun fullReportEnvelope(regon: String, reportName: String) = soapEnvelope(
        action = GusActions.DANE_POBIERZ_PELNY,
        body = """<DanePobierzPelnyRaport xmlns="http://CIS/BIR/PUBL/2014/07">
                    <pRegon>${escapeXml(regon)}</pRegon>
                    <pNazwaRaportu>${escapeXml(reportName)}</pNazwaRaportu>
                  </DanePobierzPelnyRaport>"""
    )

    private fun soapEnvelope(action: String, body: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:a="http://www.w3.org/2005/08/addressing">
  <s:Header>
    <a:Action s:mustUnderstand="1">$action</a:Action>
    <a:To s:mustUnderstand="1">$endpointUrl</a:To>
  </s:Header>
  <s:Body>
    $body
  </s:Body>
</s:Envelope>"""

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
}

object GusReportNames {
    const val OS_PRAWNA             = "BIR11OsPrawna"
    const val OS_FIZYCZNA_OGOLNE    = "BIR11OsFizycznaDaneOgolne"
    const val JEDN_LOK_OS_PRAWNEJ   = "BIR11JednLokalnaOsPrawnej"
    const val JEDN_LOK_OS_FIZYCZNEJ = "BIR11JednLokalnaOsFizycznej"
}
