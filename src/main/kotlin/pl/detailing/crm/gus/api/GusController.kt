package pl.detailing.crm.gus.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.gus.application.GusCompanyService

/**
 * Endpoint do wyszukiwania danych firmy w rejestrze GUS/REGON na podstawie NIP.
 *
 * GET /api/v1/gus/company?nip=1234567890
 *
 * Odpowiedź pochodzi z cache (Redis, TTL 24h) lub na żywo z GUS BIR.
 * Każde wywołanie jest audytowane – NIP i studioId są logowane.
 */
@RestController
@RequestMapping("/api/v1/gus")
// NIP lookup serves customer/visit intake and invoicing.
@RequiresPermission(Permission.VISITS_CREATE, Permission.FINANCE_INVOICES)
class GusController(
    private val gusCompanyService: GusCompanyService
) {

    @GetMapping("/company")
    suspend fun getCompanyByNip(
        @RequestParam nip: String
    ): ResponseEntity<CompanyInfoResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val companyInfo = withContext(Dispatchers.IO) {
            gusCompanyService.getCompanyByNip(
                nip                  = nip,
                requestedByStudioId  = principal.studioId.toString()
            )
        }

        return ResponseEntity.ok(companyInfo.toResponse())
    }
}
