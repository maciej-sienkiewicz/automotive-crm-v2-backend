package pl.detailing.crm.ksef.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.akmf.ksef.sdk.client.interfaces.CryptographyService
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.auth.AuthKsefTokenRequest
import pl.akmf.ksef.sdk.client.model.auth.ContextIdentifier
import pl.akmf.ksef.sdk.system.KsefIntegrationMode
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.Base64

/**
 * Orchestrates the KSeF token-based authentication flow using the official SDK.
 *
 * Flow:
 * 1. GET /api/v2/auth/challenge           – one-time challenge
 * 2. encryptKsefTokenUsingPublicKey()     – SDK handles RSA/ECIes encryption
 * 3. POST /api/v2/auth/ksef-token         – submit encrypted token + NIP
 * 4. Poll GET /api/v2/auth/{ref}          – wait for status 200
 * 5. POST /api/v2/auth/token/redeem       – get final access + refresh tokens
 */
@Service
class KsefAuthService(
    private val ksefClient: KSeFClient,
    private val cryptographyService: CryptographyService,
    private val sessionCache: KsefSessionCache,
    private val credentialsRepository: KsefCredentialsRepository
) {
    private val log = LoggerFactory.getLogger(KsefAuthService::class.java)

    companion object {
        private const val AUTH_POLL_MAX_ATTEMPTS = 20
        private const val AUTH_POLL_DELAY_MS = 3_000L
        private const val AUTH_STATUS_READY_CODE = 200
    }

    fun getValidAccessToken(studioId: StudioId): String =
        sessionCache.get(studioId)?.accessToken ?: authenticate(studioId).accessToken

    fun authenticate(studioId: StudioId): KsefSession {
        val credentials = credentialsRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException(
                "No KSeF credentials configured for this studio. " +
                    "Please configure your NIP and KSeF token first."
            )

        // Re-initialize if crypto service is in offline mode (e.g. first startup without network)
        if (cryptographyService.ksefIntegrationMode == KsefIntegrationMode.OFFLINE) {
            log.warn("KSeF CryptographyService is in offline mode – re-initializing")
            cryptographyService.initCryptographyService()
            if (cryptographyService.ksefIntegrationMode == KsefIntegrationMode.OFFLINE) {
                throw ValidationException(
                    "Cannot reach KSeF API to fetch the public key. " +
                        "Check your network connectivity and try again."
                )
            }
        }

        log.info("Starting KSeF authentication for studio {}", studioId)

        // Step 1: challenge
        val challenge = ksefClient.getAuthChallenge()
        log.debug("KSeF challenge obtained: {}", challenge.challenge)

        // Step 2: SDK encrypts the token and appends the challenge timestamp
        val encryptedBytes = cryptographyService.encryptKsefTokenUsingPublicKey(
            credentials.ksefToken,
            challenge.timestamp
        )
        val encryptedToken = Base64.getEncoder().encodeToString(encryptedBytes)

        // Step 3: submit
        val authRequest = AuthKsefTokenRequest(
            challenge.challenge,
            ContextIdentifier(ContextIdentifier.IdentifierType.NIP, credentials.nip),
            encryptedToken,
            null  // no IP address policy
        )
        val submitResponse = ksefClient.authenticateByKSeFToken(authRequest)
        log.debug("KSeF auth submitted, referenceNumber={}", submitResponse.referenceNumber)

        // Step 4: poll
        val tempToken = submitResponse.authenticationToken.token
        waitForAuthReady(submitResponse.referenceNumber, tempToken)

        // Step 5: redeem
        val tokenResponse = ksefClient.redeemToken(tempToken)

        val session = KsefSession(
            accessToken = tokenResponse.accessToken.token,
            refreshToken = tokenResponse.refreshToken.token,
            accessTokenValidUntil = tokenResponse.accessToken.validUntil,
            refreshTokenValidUntil = tokenResponse.refreshToken.validUntil
        )

        sessionCache.put(studioId, session)
        log.info(
            "KSeF authentication successful for studio {}, token valid until {}",
            studioId, tokenResponse.accessToken.validUntil
        )

        return session
    }

    private fun waitForAuthReady(referenceNumber: String, tempToken: String) {
        repeat(AUTH_POLL_MAX_ATTEMPTS) { attempt ->
            Thread.sleep(AUTH_POLL_DELAY_MS)
            val status = ksefClient.getAuthStatus(referenceNumber, tempToken)
            if (status.status.code == AUTH_STATUS_READY_CODE) {
                log.debug("KSeF auth ready after {} poll(s)", attempt + 1)
                return
            }
            log.debug(
                "KSeF auth status code={}, attempt={}/{}",
                status.status.code, attempt + 1, AUTH_POLL_MAX_ATTEMPTS
            )
        }
        throw RuntimeException(
            "KSeF authentication did not complete after $AUTH_POLL_MAX_ATTEMPTS attempts " +
                "(referenceNumber=$referenceNumber)"
        )
    }
}
