package pl.detailing.crm.carddav

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.shared.ForbiddenException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/carddav/{tenantId}")
class CardDavController(
    private val cardDavService: CardDavService,
    private val xmlBuilder: CardDavXmlBuilder,
    private val vCardFormatter: VCardFormatter
) {

    // OPTIONS — capability advertisement for CardDAV clients
    @RequestMapping(value = ["", "/", "/contacts", "/contacts/", "/contacts/{customerId}.vcf"])
    fun handleOptions(
        @PathVariable tenantId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        when (request.method.uppercase()) {
            "OPTIONS" -> serveOptions(response)
            "PROPFIND" -> handlePropfind(tenantId, request, response)
            "REPORT" -> handleReport(tenantId, request, response)
            "GET" -> {
                val customerId = extractCustomerId(request.requestURI)
                if (customerId != null) serveVCard(tenantId, customerId, response)
                else response.sendError(HttpServletResponse.SC_NOT_FOUND)
            }
            else -> response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
        }
    }

    // ── Verb handlers ────────────────────────────────────────────────────────

    private fun serveOptions(response: HttpServletResponse) {
        response.setHeader("Allow", "OPTIONS, GET, PROPFIND, REPORT")
        response.setHeader("DAV", "1, 2, addressbook")
        response.status = HttpServletResponse.SC_OK
    }

    private fun handlePropfind(tenantId: UUID, request: HttpServletRequest, response: HttpServletResponse) {
        assertTenant(tenantId)

        val baseUrl = buildBaseUrl(request)
        val xml = xmlBuilder.propfindAddressBookRoot(tenantId, baseUrl)

        response.status = MULTI_STATUS
        response.contentType = CONTENT_TYPE_XML
        response.writer.write(xml)
    }

    private fun handleReport(tenantId: UUID, request: HttpServletRequest, response: HttpServletResponse) {
        assertTenant(tenantId)

        val customers = cardDavService.getContactsForTenant(tenantId)
        val baseUrl = buildBaseUrl(request)
        val xml = xmlBuilder.reportAddressBookQuery(tenantId, customers, vCardFormatter, baseUrl)

        response.status = MULTI_STATUS
        response.contentType = CONTENT_TYPE_XML
        response.writer.write(xml)
    }

    private fun serveVCard(tenantId: UUID, customerId: UUID, response: HttpServletResponse) {
        assertTenant(tenantId)

        val customer = cardDavService.getContactForTenant(tenantId, customerId)
        val vcard = vCardFormatter.format(customer)
        val etag = vCardFormatter.etag(customer)

        response.status = HttpServletResponse.SC_OK
        response.contentType = "text/vcard; charset=utf-8"
        response.setHeader("ETag", "\"$etag\"")
        response.writer.write(vcard)
    }

    // ── Security ─────────────────────────────────────────────────────────────

    private fun assertTenant(tenantId: UUID) {
        val principal = SecurityContextHolder.getContext().authentication?.principal
            as? CardDavUserDetails
            ?: throw ForbiddenException("Invalid authentication context")

        cardDavService.assertTenantOwnership(principal.studioId, tenantId)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildBaseUrl(request: HttpServletRequest): String {
        val scheme = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host") ?: request.serverName
        val port = request.serverPort
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun extractCustomerId(uri: String): UUID? {
        val match = VCF_PATTERN.find(uri) ?: return null
        return runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull()
    }

    companion object {
        private const val MULTI_STATUS = 207
        private const val CONTENT_TYPE_XML = "application/xml; charset=utf-8"
        private val VCF_PATTERN = Regex("""/contacts/([0-9a-fA-F-]{36})\.vcf$""")
    }
}
