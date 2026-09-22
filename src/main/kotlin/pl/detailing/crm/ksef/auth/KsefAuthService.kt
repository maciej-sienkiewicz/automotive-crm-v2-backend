package pl.detailing.crm.ksef.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.akmf.ksef.sdk.client.interfaces.CryptographyService
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.auth.AuthKsefTokenRequest
import pl.akmf.ksef.sdk.client.model.auth.ContextIdentifier
import pl.akmf.ksef.sdk.system.KsefIntegrationMode
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.metrics.KsefTenantContext
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.Base64

/**
 * KSeF zwrócił w statusie uwierzytelnienia werdykt terminalny (albo nie zwrócił
 * żadnego w limicie prób). Ponawianie odpytywania tej samej operacji nic nie da —
 * kolejna próba to nowe wyzwanie i nowe uwierzytelnienie.
 *
 * [statusCode] to kod przetwarzania z `GET /auth/{referenceNumber}` (nie kod HTTP);
 * `null` oznacza wyczerpanie limitu prób bez werdyktu.
 */
class KsefAuthException(
    message: String,
    val statusCode: Int?,
    val referenceNumber: String
) : RuntimeException(message)

/**
 * Orchestrates the KSeF token-based authentication flow using the official SDK.
 *
 * Flow:
 * 1. GET /api/v2/auth/challenge           – one-time challenge
 * 2. encryptKsefTokenUsingPublicKey()     – SDK handles RSA/ECIes encryption
 * 3. POST /api/v2/auth/ksef-token         – submit encrypted token + NIP
 * 4. Poll GET /api/v2/auth/{ref}          – 100 = w toku, 200 = gotowe, reszta = błąd terminalny
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

        /** Kody przetwarzania z `GET /auth/{referenceNumber}` (KSeF API 2.0). */
        private const val AUTH_STATUS_IN_PROGRESS_CODE = 100
        private const val AUTH_STATUS_READY_CODE = 200

        /** „Brak przypisanych uprawnień" — token przyjęty, ale nic mu w tym kontekście nie nadano. */
        internal const val AUTH_STATUS_NO_PERMISSIONS_CODE = 415

        /** „Uwierzytelnianie zakończone niepowodzeniem z powodu błędnego tokenu". */
        internal const val AUTH_STATUS_INVALID_TOKEN_CODE = 450
    }

    /** Podmienialne w testach — produkcyjnie zwykłe uśpienie wątku między odpytaniami. */
    internal var pollSleeper: (Long) -> Unit = { Thread.sleep(it) }

    fun getValidAccessToken(studioId: StudioId): String =
        sessionCache.get(studioId)?.accessToken ?: authenticate(studioId).accessToken

    /**
     * Uwierzytelnienie to pięć żądań do KSeF plus polling — najdroższa pojedyncza
     * operacja w limicie. Kontekst najemcy ustawiamy tutaj, żeby ten ruch nie
     * ginął w metrykach, gdy uwierzytelnienie startuje spoza synchronizacji
     * (np. z weryfikacji tokenu w ustawieniach).
     */
    fun authenticate(studioId: StudioId): KsefSession = KsefTenantContext.withStudio(studioId) {
        doAuthenticate(studioId)
    }

    private fun doAuthenticate(studioId: StudioId): KsefSession {
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

    /**
     * Odpytuje status uwierzytelnienia. Czeka wyłącznie na kod „w toku" (100);
     * każdy inny kod poza 200 to werdykt terminalny — KSeF nie zmieni zdania przy
     * kolejnym odpytaniu, więc dalsze próby tylko zjadają limit żądań kontekstu NIP.
     *
     * Brak kodu w odpowiedzi nie jest werdyktem, więc liczy się jak „w toku".
     */
    internal fun waitForAuthReady(referenceNumber: String, tempToken: String) {
        for (attempt in 1..AUTH_POLL_MAX_ATTEMPTS) {
            pollSleeper(AUTH_POLL_DELAY_MS)
            val status = ksefClient.getAuthStatus(referenceNumber, tempToken).status
            val code = status?.code
            when {
                code == AUTH_STATUS_READY_CODE -> {
                    log.debug("KSeF auth ready after {} poll(s)", attempt)
                    return
                }

                code == null || code == AUTH_STATUS_IN_PROGRESS_CODE -> log.debug(
                    "KSeF auth status code={}, attempt={}/{}",
                    code, attempt, AUTH_POLL_MAX_ATTEMPTS
                )

                else -> {
                    log.warn(
                        "KSeF auth failed: code={} description={} details={} referenceNumber={}",
                        code, status?.description, status?.details, referenceNumber
                    )
                    throw KsefAuthException(
                        describeAuthFailure(code, status?.description, status?.details),
                        statusCode = code,
                        referenceNumber = referenceNumber
                    )
                }
            }
        }
        throw KsefAuthException(
            "KSeF nie zakończył uwierzytelnienia w ${AUTH_POLL_MAX_ATTEMPTS} próbach " +
                "(numer referencyjny $referenceNumber). Spróbuj ponownie później.",
            statusCode = null,
            referenceNumber = referenceNumber
        )
    }

    /**
     * Komunikat trafia do statusu synchronizacji i do weryfikacji tokenu w ustawieniach,
     * więc dla kodów, które naprawia właściciel studia, mówi, co ma sprawdzić.
     * Oryginalny opis i szczegóły z KSeF zostają — rozróżniają np. token unieważniony
     * od nieaktywnego.
     */
    internal fun describeAuthFailure(code: Int, description: String?, details: List<String>?): String {
        val fromKsef = listOfNotNull(
            description?.takeIf { it.isNotBlank() },
            details?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString("; ")
        ).joinToString(" — ").ifEmpty { "brak opisu" }

        return when (code) {
            AUTH_STATUS_INVALID_TOKEN_CODE ->
                "KSeF odrzucił token (kod 450): $fromKsef. Sprawdź, czy wklejono pełny token " +
                    "skopiowany z portalu KSeF, czy nie został unieważniony lub nie jest jeszcze " +
                    "nieaktywny, czy wygenerowano go w tym samym środowisku KSeF (produkcja / test / demo), " +
                    "z którym łączy się CRM, i czy NIP w ustawieniach to NIP, dla którego wygenerowano token."

            AUTH_STATUS_NO_PERMISSIONS_CODE ->
                "KSeF przyjął token, ale nie ma on uprawnień w kontekście tego NIP-u (kod 415): $fromKsef. " +
                    "Wygeneruj token z uprawnieniem do przeglądania faktur (InvoiceRead)."

            in 400..599 -> "KSeF odrzucił uwierzytelnienie (kod $code): $fromKsef"

            else -> "KSeF zwrócił nieoczekiwany status uwierzytelnienia (kod $code): $fromKsef"
        }
    }
}
