package pl.detailing.crm.ksef.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.permission.search.PermissionState
import pl.akmf.ksef.sdk.client.model.permission.search.QueryPersonalGrantRequest
import pl.detailing.crm.shared.StudioId
import java.util.Base64

/**
 * Verifies the studio's saved KSeF token: is it accepted by KSeF at all, and
 * which permissions were actually granted to it. Used by the settings view to
 * tell the owner "your token works, but it can't issue invoices" instead of
 * failing later at the first invoice.
 *
 * Permission detection, in order of preference:
 * 1. The access-token JWT returned by the auth flow — KSeF embeds the effective
 *    permissions of the authenticated context in its payload claims. We scan the
 *    whole payload for known permission names rather than assuming a specific
 *    claim key, so a claim rename on the KSeF side degrades to the fallback
 *    instead of a wrong "no permissions" answer.
 * 2. GET-style query POST /permissions/query/personal/grants (SDK
 *    [KSeFClient.searchPersonalGrantPermission]) — an extra API call, but an
 *    explicit contract.
 *
 * Note there is no separate "UPO" permission in KSeF: UPO is downloadable for
 * sessions the token itself opened, so InvoiceWrite covers it. The controller
 * maps permissions to user-facing capabilities accordingly.
 */
@Service
class KsefTokenVerifier(
    private val ksefAuthService: KsefAuthService,
    private val ksefClient: KSeFClient,
    private val sessionCache: KsefSessionCache,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(KsefTokenVerifier::class.java)

    companion object {
        /** Permission names as KSeF spells them (TokenPermissionType values in the SDK). */
        val KNOWN_PERMISSIONS = setOf(
            "InvoiceRead", "InvoiceWrite",
            "CredentialsRead", "CredentialsManage",
            "SubunitManage", "EnforcementOperations",
            "Introspection", "PeppolId", "VatUeManage"
        )
        private const val GRANTS_PAGE_SIZE = 100
    }

    data class VerificationResult(
        val tokenValid: Boolean,
        /** Detected permission names (KSeF spelling). Meaningful only when [permissionsKnown]. */
        val permissions: Set<String>,
        /** False when the token authenticated but neither source yielded a permission list. */
        val permissionsKnown: Boolean,
        val errorMessage: String?
    )

    /**
     * Always verifies the token that is saved RIGHT NOW: the cached session is
     * dropped first, so a freshly pasted token is what gets authenticated.
     * Throws [pl.detailing.crm.shared.EntityNotFoundException] when no
     * credentials are configured (propagated from [KsefAuthService]).
     */
    fun verify(studioId: StudioId): VerificationResult {
        sessionCache.invalidate(studioId)

        val session = try {
            ksefAuthService.authenticate(studioId)
        } catch (e: pl.detailing.crm.shared.EntityNotFoundException) {
            throw e
        } catch (e: Exception) {
            log.info("KSeF token verification failed at authentication for studio {}: {}", studioId, e.message)
            return VerificationResult(
                tokenValid = false,
                permissions = emptySet(),
                permissionsKnown = false,
                errorMessage = "KSeF odrzucił token: ${e.message ?: "nieznany błąd uwierzytelnienia"}"
            )
        }

        val fromJwt = permissionsFromAccessToken(session.accessToken)
        if (fromJwt.isNotEmpty()) {
            return VerificationResult(tokenValid = true, permissions = fromJwt, permissionsKnown = true, errorMessage = null)
        }

        return try {
            val fromApi = permissionsFromGrantsApi(session.accessToken)
            VerificationResult(tokenValid = true, permissions = fromApi, permissionsKnown = true, errorMessage = null)
        } catch (e: Exception) {
            log.warn("KSeF personal-grants query failed for studio {}: {}", studioId, e.message)
            VerificationResult(
                tokenValid = true,
                permissions = emptySet(),
                permissionsKnown = false,
                errorMessage = "Token jest poprawny, ale nie udało się odczytać listy uprawnień."
            )
        }
    }

    /**
     * Decodes the JWT payload (no signature check — we received the token
     * directly from KSeF over TLS) and collects every known permission name
     * appearing anywhere in it.
     */
    private fun permissionsFromAccessToken(accessToken: String): Set<String> = try {
        val parts = accessToken.split(".")
        if (parts.size < 2) emptySet()
        else {
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            val found = mutableSetOf<String>()
            collectKnownPermissions(objectMapper.readTree(payload), found)
            found
        }
    } catch (e: Exception) {
        log.debug("Could not decode KSeF access token payload: {}", e.message)
        emptySet()
    }

    private fun collectKnownPermissions(node: JsonNode, out: MutableSet<String>) {
        when {
            node.isTextual -> KNOWN_PERMISSIONS.firstOrNull { it.equals(node.asText(), ignoreCase = true) }
                ?.let { out.add(it) }
            node.isArray || node.isObject -> node.forEach { collectKnownPermissions(it, out) }
        }
    }

    private fun permissionsFromGrantsApi(accessToken: String): Set<String> {
        val response = ksefClient.searchPersonalGrantPermission(
            QueryPersonalGrantRequest(), 0, GRANTS_PAGE_SIZE, accessToken
        )
        return response.permissions.orEmpty()
            .filter { it.permissionState == null || it.permissionState == PermissionState.ACTIVE }
            .mapNotNull { it.permissionScope?.value }
            .toSet()
    }
}
