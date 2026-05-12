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
        val entries = GusXmlParser.parseSearchResult(searchXml)

        if (entries.isEmpty()) {
            throw CompanyNotFoundException(nip)
        }

        // Preferuj wpis z rejestru REGON (SilosID 4–6) nad CEIDG (1–3)
        val primaryEntry = entries
            .filter { it.regon.isNotBlank() }
            .maxByOrNull { it.silosId }
            ?: entries.first()

        val reportName = resolveReportName(primaryEntry.entityType)
        log.debug("Using report '{}' for REGON={}", reportName, primaryEntry.regon)

        val reportXml = soapClient.fetchFullReport(primaryEntry.regon, reportName, sessionId)
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

    private fun resolveReportName(entityType: String): String = when (entityType.uppercase()) {
        "P"  -> GusReportNames.OS_PRAWNA
        "F"  -> GusReportNames.OS_FIZYCZNA_OGOLNE
        "LP" -> GusReportNames.JEDN_LOK_OS_PRAWNEJ
        "LF" -> GusReportNames.JEDN_LOK_OS_FIZYCZNEJ
        else -> GusReportNames.OS_PRAWNA
    }
}
