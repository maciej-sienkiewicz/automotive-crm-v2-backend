package pl.detailing.crm.gus.application

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.cache.annotation.Cacheable
import pl.detailing.crm.gus.domain.CompanyInfo
import pl.detailing.crm.gus.exception.InvalidNipException
import pl.detailing.crm.gus.port.CompanyDataProvider
import java.time.Instant

/**
 * Punkt wejścia dla logiki pobierania danych firmy po NIP.
 *
 * Odpowiada za:
 *  - walidację NIP (algorytm sumy kontrolnej),
 *  - cache (wynik trafia do Redisa na czas określony w [GusProperties.cacheTtlHours]),
 *  - logowanie audytowe (kto, kiedy, jaki NIP, wynik, czas odpowiedzi).
 *
 * Nie zna żadnych szczegółów protokołu SOAP ani struktury XML GUS.
 * Całą komunikację deleguje do [CompanyDataProvider].
 */
class GusCompanyService(
    private val companyDataProvider: CompanyDataProvider
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Cacheable(
        cacheNames = ["gus-company"],
        key = "#nip",
        cacheManager = "gusCacheManager"
    )
    fun getCompanyByNip(nip: String, requestedByStudioId: String): CompanyInfo {
        val normalizedNip = nip.replace("-", "").trim()
        validateNip(normalizedNip)

        val start = Instant.now()

        MDC.put("gus.nip", normalizedNip)
        MDC.put("gus.studioId", requestedByStudioId)

        return try {
            log.info("GUS lookup START – NIP={}, studio={}", normalizedNip, requestedByStudioId)
            val result = companyDataProvider.findByNip(normalizedNip)
            val elapsed = Instant.now().toEpochMilli() - start.toEpochMilli()
            log.info(
                "GUS lookup OK – NIP={}, company='{}', studio={}, elapsed={}ms",
                normalizedNip, result.name, requestedByStudioId, elapsed
            )
            result
        } catch (ex: Exception) {
            val elapsed = Instant.now().toEpochMilli() - start.toEpochMilli()
            log.warn(
                "GUS lookup FAILED – NIP={}, studio={}, elapsed={}ms, reason={}",
                normalizedNip, requestedByStudioId, elapsed, ex.message
            )
            throw ex
        } finally {
            MDC.remove("gus.nip")
            MDC.remove("gus.studioId")
        }
    }

    // ─── NIP validation ───────────────────────────────────────────────────────

    private fun validateNip(nip: String) {
        if (!NIP_PATTERN.matches(nip)) throw InvalidNipException(nip)

        val weights = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)
        val checksum = nip.take(9).mapIndexed { i, c ->
            c.digitToInt() * weights[i]
        }.sum() % 11

        // Checksum == 10 is always invalid per GUS spec
        if (checksum == 10 || checksum != nip[9].digitToInt()) {
            throw InvalidNipException(nip)
        }
    }

    companion object {
        private val NIP_PATTERN = Regex("^\\d{10}$")
    }
}
