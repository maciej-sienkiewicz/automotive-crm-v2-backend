package pl.detailing.crm.ksef.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.client.KsefApiClient
import pl.detailing.crm.ksef.client.KsefApiException
import pl.detailing.crm.ksef.client.KsefAuthKsefTokenRequest
import pl.detailing.crm.ksef.client.KsefContextIdentifier
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId

/**
 * Orchestrates the full KSeF token-based authentication flow:
 *
 * 1. GET /auth/challenge  – obtain a one-time challenge
 * 2. Encrypt the stored KSeF token with the KSeF RSA public key
 * 3. POST /auth/ksef-token  – submit encrypted token + NIP context
 * 4. Poll GET /auth/{referenceNumber} until status 200
 * 5. POST /auth/token/redeem  – obtain the final access + refresh tokens
 * 6. Cache the resulting session for subsequent API calls
 */
@Service
class KsefAuthService(
    private val ksefApiClient: KsefApiClient,
    private val encryptionService: KsefTokenEncryptionService,
    private val sessionCache: KsefSessionCache,
    private val credentialsRepository: KsefCredentialsRepository
) {
    private val log = LoggerFactory.getLogger(KsefAuthService::class.java)

    companion object {
        private const val AUTH_POLL_MAX_ATTEMPTS = 20
        private const val AUTH_POLL_DELAY_MS = 3_000L
        private const val AUTH_STATUS_READY_CODE = 200
    }

    /**
     * Returns a valid KSeF access token for the given studio.
     * Uses the cached session if still valid; otherwise re-authenticates.
     */
    fun getValidAccessToken(studioId: StudioId): String {
        val cached = sessionCache.get(studioId)
        if (cached != null) {
            return cached.accessToken
        }
        return authenticate(studioId).accessToken
    }

    /**
     * Forces a new authentication and returns the resulting session.
     */
    fun authenticate(studioId: StudioId): KsefSession {
        val credentials = credentialsRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException("No KSeF credentials configured for this studio. " +
                "Please configure your NIP and KSeF token first.")

        log.info("Starting KSeF authentication for studio {}", studioId)

        // Step 1: challenge
        val challenge = ksefApiClient.getChallenge()
        log.debug("KSeF challenge obtained: {}", challenge.challenge)

        // Step 2: encrypt the stored KSeF token
        val encryptedToken = encryptionService.encryptToken(
            ksefToken = credentials.ksefToken,
            challengeTimestampMs = challenge.timestampMs
        )

        // Step 3: submit auth request
        val authRequest = KsefAuthKsefTokenRequest(
            challenge = challenge.challenge,
            contextIdentifier = KsefContextIdentifier(
                type = "Nip",
                value = credentials.nip
            ),
            encryptedToken = encryptedToken
        )
        val submitResponse = ksefApiClient.submitKsefTokenAuth(authRequest)
        log.debug("KSeF auth submitted, referenceNumber={}", submitResponse.referenceNumber)

        // Step 4: poll for completion
        val tempToken = submitResponse.authenticationToken.token
        waitForAuthReady(submitResponse.referenceNumber, tempToken)

        // Step 5: redeem final tokens
        val tokenResponse = ksefApiClient.redeemToken(tempToken)

        val session = KsefSession(
            accessToken = tokenResponse.accessToken.token,
            refreshToken = tokenResponse.refreshToken.token,
            accessTokenValidUntil = tokenResponse.accessToken.validUntil,
            refreshTokenValidUntil = tokenResponse.refreshToken.validUntil
        )

        sessionCache.put(studioId, session)
        log.info("KSeF authentication successful for studio {}, token valid until {}",
            studioId, tokenResponse.accessToken.validUntil)

        return session
    }

    private fun waitForAuthReady(referenceNumber: String, tempToken: String) {
        repeat(AUTH_POLL_MAX_ATTEMPTS) { attempt ->
            Thread.sleep(AUTH_POLL_DELAY_MS)
            val status = ksefApiClient.getAuthStatus(referenceNumber, tempToken)
            if (status.status.code == AUTH_STATUS_READY_CODE) {
                log.debug("KSeF auth ready after {} poll(s)", attempt + 1)
                return
            }
            log.debug("KSeF auth status code={}, attempt={}/{}", status.status.code, attempt + 1, AUTH_POLL_MAX_ATTEMPTS)
        }
        throw KsefApiException(
            "KSeF authentication did not complete after $AUTH_POLL_MAX_ATTEMPTS attempts " +
                "(referenceNumber=$referenceNumber)"
        )
    }
}
