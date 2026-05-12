package pl.detailing.crm.gus.adapter.bir

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.retry.Retry
import org.slf4j.LoggerFactory
import pl.detailing.crm.gus.adapter.bir.mapper.GusCompanyMapper
import pl.detailing.crm.gus.adapter.bir.parser.GusXmlParser
import pl.detailing.crm.gus.adapter.bir.soap.GusRawSoapClient
import pl.detailing.crm.gus.adapter.bir.soap.GusReportNames
import pl.detailing.crm.gus.domain.CompanyInfo
import pl.detailing.crm.gus.exception.CompanyNotFoundException
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import pl.detailing.crm.gus.port.CompanyDataProvider

/**
 * Implementacja portu [CompanyDataProvider] oparta na GUS BIR SOAP API (ver. 1.1).
 *
 * Przepływ dla danego NIP:
 *  1. Pobierz aktywną sesję (auto-refresh gdy wygasła).
 *  2. DaneSzukajPodmioty(NIP) → lista [GusSearchEntry].
 *  3. Wybierz wpis główny (SilosID ≥ 4 ma pierwszeństwo jako wpis REGON).
 *  4. DanePobierzPelnyRaport(REGON, reportName) → [GusFullReportEntry].
 *  5. GusCompanyMapper → [CompanyInfo].
 *
 * Odporność:
 *  - [Retry] – ponawia przy [GusServiceUnavailableException] (błędy sieci, timeouty).
 *  - [CircuitBreaker] – otwiera się po przekroczeniu progu błędów; chroni przed
 *    nakładaniem się timeoutów gdy GUS jest niedostępny.
 */
class GusCompanyDataProviderAdapter(
    private val soapClient: GusRawSoapClient,
    private val sessionManager: GusSessionManager,
    private val circuitBreaker: CircuitBreaker,
    private val retry: Retry
) : CompanyDataProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun findByNip(nip: String): CompanyInfo {
        return try {
            executeWithResilience { doFindByNip(nip) }
        } catch (ex: CallNotPermittedException) {
            throw GusServiceUnavailableException(
                "Usługa GUS BIR jest chwilowo niedostępna (Circuit Breaker otwarty). Spróbuj za chwilę.",
                ex
            )
        }
    }

    // ─── Core logic ───────────────────────────────────────────────────────────

    private fun doFindByNip(nip: String): CompanyInfo {
        val sessionId = sessionManager.getActiveSessionId()

        val searchXml = soapClient.searchByNip(nip, sessionId)
        if (log.isDebugEnabled) log.debug("GUS DaneSzukajPodmioty raw XML for NIP={}: {}", nip, searchXml)
        val entries = GusXmlParser.parseSearchResult(searchXml)

        if (entries.isEmpty()) {
            // Pusta odpowiedź może oznaczać "nie znaleziono" LUB wygasłą sesję.
            // Dokumentacja GUS zaleca weryfikację przez GetValue(StatusSesji).
            val sessionStatus = soapClient.getSessionStatus(sessionId)
            if (sessionStatus != "1") {
                log.warn("GUS session expired (StatusSesji={}) – invalidating for retry", sessionStatus)
                sessionManager.invalidate()
                throw GusServiceUnavailableException(
                    "Sesja GUS wygasła (StatusSesji=$sessionStatus) – ponawiam logowanie"
                )
            }
            throw CompanyNotFoundException(nip)
        }

        // Prefer main entity types (P/F) over local units (LP/LF).
        // Among F-type entries, prefer active (no end date) and lower SilosID:
        //   SilosID=1 (CEIDG, active) > SilosID=2 (Rolnicza) > SilosID=3 (Pozostala) > SilosID=4 (historical/deleted)
        val primaryEntry = entries
            .filter { it.regon.isNotBlank() }
            .let { valid ->
                val main = valid.filter { it.entityType.uppercase() in setOf("P", "F") }
                main.ifEmpty { valid }
            }
            .let { candidates ->
                val active = candidates.filter { it.activityEndDate.isNullOrBlank() }
                active.ifEmpty { candidates }
            }
            .minByOrNull { it.silosId }
            ?: entries.first()

        val reportName = resolveReportName(primaryEntry.entityType, primaryEntry.silosId)
        log.debug("Using report '{}' for REGON={}", reportName, primaryEntry.regon)

        val reportXml = try {
            soapClient.fetchFullReport(primaryEntry.regon, reportName, sessionId).also { xml ->
                if (log.isDebugEnabled) log.debug("GUS DanePobierzPelnyRaport raw XML for REGON={}: {}", primaryEntry.regon, xml)
            }
        } catch (ex: GusServiceUnavailableException) {
            // Pusta odpowiedź DanePobierzPelnyRaport też może być symptomem wygasłej sesji
            val sessionStatus = runCatching { soapClient.getSessionStatus(sessionId) }.getOrDefault("1")
            if (sessionStatus != "1") {
                log.warn("GUS session expired during report fetch (StatusSesji={}) – invalidating for retry", sessionStatus)
                sessionManager.invalidate()
            }
            throw ex
        }
        val report = GusXmlParser.parseFullReport(reportXml)

        if (report?.errorCode != null) {
            log.warn("GUS returned error code {} for REGON={}", report.errorCode, primaryEntry.regon)
            if (report.errorCode == "4") throw CompanyNotFoundException(nip)
        }

        return GusCompanyMapper.toCompanyInfo(primaryEntry, report)
    }

    // ─── Resilience wrapper ───────────────────────────────────────────────────

    /**
     * Owija wywołanie w Retry + CircuitBreaker używając blokującego API Resilience4j.
     * Sesja jest inwalidowana tylko wtedy gdy błąd wystąpił PO zalogowaniu (tj. wywołanie
     * searchByNip/fetchFullReport zwróciło komunikację o wygaśnięciu sesji) – nie przy błędzie
     * samego Zaloguj, gdzie sesja i tak nie istnieje.
     * [CallNotPermittedException] (CB otwarty) propaguje się do wywołującego.
     */
    private fun <T> executeWithResilience(block: () -> T): T =
        retry.executeCallable {
            circuitBreaker.executeCallable {
                block()
            }
        }

    // ─── Report name resolution ───────────────────────────────────────────────

    // BIR11OsFizycznaDaneOgolne has no address fields – address is in activity-specific reports.
    // SilosID determines which activity type the physical-person entity belongs to.
    private fun resolveReportName(entityType: String, silosId: Int = 0): String =
        when (entityType.uppercase()) {
            "P"  -> GusReportNames.OS_PRAWNA
            "F"  -> when (silosId) {
                1    -> GusReportNames.OS_FIZYCZNA_CEIDG
                2    -> GusReportNames.OS_FIZYCZNA_ROLNICZA
                3    -> GusReportNames.OS_FIZYCZNA_POZOSTALA
                4    -> GusReportNames.OS_FIZYCZNA_SKRESLONA
                else -> GusReportNames.OS_FIZYCZNA_OGOLNE
            }
            "LP" -> GusReportNames.JEDN_LOK_OS_PRAWNEJ
            "LF" -> GusReportNames.JEDN_LOK_OS_FIZYCZNEJ
            else -> GusReportNames.OS_PRAWNA
        }
}
