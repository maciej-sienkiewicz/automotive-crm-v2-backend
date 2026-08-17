package pl.detailing.crm.ksef.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.auth.AuthenticationTokenStatus
import pl.detailing.crm.shared.StudioId

/**
 * Verifies the studio's saved KSeF token: is it accepted by KSeF at all, and
 * which permissions were actually granted to it. Used by the settings view to
 * tell the owner "your token works, but it can't issue invoices" instead of
 * failing later at the first invoice.
 *
 * Permission source: [KSeFClient.getKsefToken] returns [AuthenticationToken.requestedPermissions]
 * for a KSeF-side token, which is the token's OWN authoritative permission list —
 * not something inferred from the access-token JWT or from person-to-person
 * permission grants (those are a different KSeF concept entirely: delegated
 * permissions between people/entities, not a self-generated API token's own
 * scope, and were empty for every self-generated token during manual testing).
 *
 * The catch: KSeF's token API identifies tokens by a `referenceNumber` assigned
 * at generation time, which we never see — the owner pastes the raw token value
 * from the KSeF portal, not its reference number. So instead of looking up "the"
 * token directly, we list this context's ACTIVE tokens via [KSeFClient.queryKsefTokens]
 * right after authenticating with it, and take the one with the most recent
 * `lastUseDate` — that's the token we just used, since authenticating updates it.
 * This is a heuristic, not an exact match, so a studio with several tokens
 * authenticating concurrently could in theory pick the wrong one; noted in case
 * this needs tightening later.
 *
 * Note there is no separate "UPO" permission in KSeF: UPO is downloadable for
 * sessions the token itself opened, so InvoiceWrite covers it. The controller
 * maps permissions to user-facing capabilities accordingly.
 */
@Service
class KsefTokenVerifier(
    private val ksefAuthService: KsefAuthService,
    private val ksefClient: KSeFClient,
    private val sessionCache: KsefSessionCache
) {
    private val log = LoggerFactory.getLogger(KsefTokenVerifier::class.java)

    companion object {
        private const val TOKENS_PAGE_SIZE = 50
    }

    data class VerificationResult(
        val tokenValid: Boolean,
        /** Detected permission names (KSeF spelling, e.g. "InvoiceWrite"). Meaningful only when [permissionsKnown]. */
        val permissions: Set<String>,
        /** False when the token authenticated but its entry couldn't be matched among the context's active tokens. */
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

        return try {
            val tokens = ksefClient.queryKsefTokens(
                listOf(AuthenticationTokenStatus.ACTIVE), null, null, null, null,
                TOKENS_PAGE_SIZE, session.accessToken
            ).tokens.orEmpty()

            val justUsed = tokens.maxByOrNull { it.lastUseDate ?: it.dateCreated }
            if (justUsed == null) {
                log.warn("KSeF authenticated for studio {} but no active tokens were returned", studioId)
                VerificationResult(
                    tokenValid = true,
                    permissions = emptySet(),
                    permissionsKnown = false,
                    errorMessage = "Token jest poprawny, ale nie udało się odnaleźć jego uprawnień na liście tokenów w KSeF."
                )
            } else {
                val permissions = justUsed.requestedPermissions.orEmpty().mapNotNull { it.value }.toSet()
                VerificationResult(tokenValid = true, permissions = permissions, permissionsKnown = true, errorMessage = null)
            }
        } catch (e: Exception) {
            log.warn("KSeF queryKsefTokens failed for studio {}: {}", studioId, e.message)
            VerificationResult(
                tokenValid = true,
                permissions = emptySet(),
                permissionsKnown = false,
                errorMessage = "Token jest poprawny, ale nie udało się odczytać listy jego uprawnień."
            )
        }
    }
}
