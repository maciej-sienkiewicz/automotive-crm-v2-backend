package pl.detailing.crm.carddav

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
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

    // Apple Configuration Profile (.mobileconfig) — public endpoint, no auth required.
    // iOS scans the QR code → downloads this file → prompts "Install profile" → asks for password.
    // Deterministic UUIDs derived from tenantId so reinstalling the same profile updates rather
    // than duplicates the CardDAV account on the device.
    @GetMapping("/setup.mobileconfig", produces = ["application/x-apple-aspen-config"])
    fun serveMobileConfig(
        @PathVariable tenantId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val baseUrl = buildBaseUrl(request)
        val principalUrl = "$baseUrl/api/v1/carddav/$tenantId/"
        val host = request.getHeader("X-Forwarded-Host") ?: request.serverName

        // Two deterministic UUIDs: one for the outer profile, one for the CardDAV payload.
        // Using the tenantId itself and its "version-5" name-based variant.
        val outerUuid = tenantId
        val innerUuid = UUID.nameUUIDFromBytes("carddav:$tenantId".toByteArray())

        response.setHeader("Content-Disposition", "attachment; filename=\"crm-contacts.mobileconfig\"")
        response.writer.write(buildMobileConfig(host, principalUrl, outerUuid, innerUuid))
    }

    private fun buildMobileConfig(host: String, principalUrl: String, outerUuid: UUID, innerUuid: UUID) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>PayloadContent</key>
            <array>
                <dict>
                    <key>CardDAVAccountDescription</key>
                    <string>CRM Kontakty</string>
                    <key>CardDAVHostName</key>
                    <string>$host</string>
                    <key>CardDAVPrincipalURL</key>
                    <string>$principalUrl</string>
                    <key>CardDAVPort</key>
                    <integer>443</integer>
                    <key>CardDAVUseSSL</key>
                    <true/>
                    <key>PayloadDescription</key>
                    <string>Synchronizacja kontaktów CRM z telefonem</string>
                    <key>PayloadDisplayName</key>
                    <string>CRM Kontakty</string>
                    <key>PayloadIdentifier</key>
                    <string>pl.detailboost.carddav.$innerUuid</string>
                    <key>PayloadType</key>
                    <string>com.apple.carddav.account</string>
                    <key>PayloadUUID</key>
                    <string>$innerUuid</string>
                    <key>PayloadVersion</key>
                    <integer>1</integer>
                </dict>
            </array>
            <key>PayloadDescription</key>
            <string>Konfiguruje synchronizację kontaktów CRM z aplikacją Kontakty</string>
            <key>PayloadDisplayName</key>
            <string>CRM Kontakty</string>
            <key>PayloadIdentifier</key>
            <string>pl.detailboost.profile.$outerUuid</string>
            <key>PayloadOrganization</key>
            <string>Detailboost</string>
            <key>PayloadRemovalDisallowed</key>
            <false/>
            <key>PayloadType</key>
            <string>Configuration</string>
            <key>PayloadUUID</key>
            <string>$outerUuid</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
        </dict>
        </plist>
    """.trimIndent()

    // Explicit OPTIONS handler — @RequestMapping without method list skips OPTIONS in Spring MVC,
    // so we need a dedicated mapping to advertise DAV capabilities correctly.
    @RequestMapping(
        value = ["", "/", "/contacts", "/contacts/", "/contacts/{customerId}.vcf"],
        method = [RequestMethod.OPTIONS]
    )
    fun handleOptionsMethod(response: HttpServletResponse) {
        serveOptions(response)
    }

    // Generic handler for WebDAV methods (PROPFIND, REPORT) and GET.
    // Empty method list matches everything except OPTIONS (handled above).
    @RequestMapping(value = ["", "/", "/contacts", "/contacts/", "/contacts/{customerId}.vcf"])
    fun handleRequest(
        @PathVariable tenantId: UUID,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        when (request.method.uppercase()) {
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
        val depth = request.getHeader("Depth") ?: "0"
        val uri = request.requestURI

        val xml = when {
            isVcfPath(uri) -> {
                val customerId = extractCustomerId(uri)
                    ?: run { response.sendError(HttpServletResponse.SC_NOT_FOUND); return }
                val customer = cardDavService.getContactForTenant(tenantId, customerId)
                xmlBuilder.propfindSingleContact(tenantId, customer, vCardFormatter, baseUrl)
            }
            isContactsPath(uri) -> {
                val customers = cardDavService.getContactsForTenant(tenantId)
                xmlBuilder.propfindAddressBook(tenantId, customers, vCardFormatter, baseUrl, depth)
            }
            else -> xmlBuilder.propfindPrincipal(tenantId, baseUrl)
        }

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
        // Behind a reverse proxy (X-Forwarded-Proto is set), serverPort reflects the internal
        // backend port (e.g. 8080), not the public port. Use X-Forwarded-Port when available;
        // otherwise fall back to the scheme default (443 for https, 80 for http).
        val port = request.getHeader("X-Forwarded-Port")?.toIntOrNull()
            ?: if (request.getHeader("X-Forwarded-Proto") != null) (if (scheme == "https") 443 else 80)
            else request.serverPort
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun isContactsPath(uri: String) = uri.contains("/contacts")

    private fun isVcfPath(uri: String) = VCF_PATTERN.containsMatchIn(uri)

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
