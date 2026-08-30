package pl.detailing.crm.carddav

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.user.infrastructure.UserRepository
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class ProvisioningLink(
    val provisioningId: UUID,
    val installUrl: String,
    val expiresAt: Instant,
)

data class ProfilePayload(
    val fileName: String,
    val xml: String,
)

/**
 * Automatyczna konfiguracja CardDAV na iPhonie.
 *
 * Zalogowany użytkownik wybija jednorazowy link; pod linkiem serwujemy profil
 * .mobileconfig z kompletem danych (host, e-mail, hasło aplikacyjne), więc na
 * telefonie nikt niczego nie przepisuje. Link działa bez sesji — profil bywa
 * pobierany inną przeglądarką niż ta z zalogowanym CRM (skan QR → Safari).
 */
@Service
class CardDavProvisioningService(
    private val appPasswordRepository: CardDavAppPasswordRepository,
    private val provisioningRepository: CardDavProvisioningRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    private val random = SecureRandom()

    /**
     * @param publicBaseUrl adres wyprowadzony z bieżącego żądania (PublicBaseUrl.of)
     *                      — link musi wskazywać domenę, przez którą użytkownik
     *                      naprawdę wszedł, a nie skonfigurowany na sztywno host.
     */
    @Transactional
    fun createProvisioning(principal: UserPrincipal, deviceName: String, publicBaseUrl: String): ProvisioningLink {
        val secret = randomToken(SECRET_BYTES)
        val appPassword = appPasswordRepository.save(
            CardDavAppPasswordEntity(
                id = UUID.randomUUID(),
                studioId = principal.studioId.value,
                userId = principal.userId.value,
                deviceName = deviceName.trim().take(120).ifBlank { "iPhone" },
                secretHash = passwordEncoder.encode(secret),
            )
        )
        val provisioning = provisioningRepository.save(
            CardDavProvisioningEntity(
                id = UUID.randomUUID(),
                appPasswordId = appPassword.id,
                token = randomToken(TOKEN_BYTES),
                secretPlain = secret,
                expiresAt = Instant.now().plusSeconds(LINK_TTL_MINUTES * 60),
            )
        )
        return ProvisioningLink(
            provisioningId = provisioning.id,
            installUrl = "$publicBaseUrl/api/public/carddav-profile/${provisioning.token}",
            expiresAt = provisioning.expiresAt,
        )
    }

    /**
     * Wydaje profil i spala link: sekret znika z bazy w tej samej transakcji,
     * w której go użyto — po pobraniu istnieje już tylko hash i sam telefon.
     */
    @Transactional
    fun redeemProfile(token: String, hostName: String): ProfilePayload {
        val provisioning = provisioningRepository.findByToken(token)
            ?: throw EntityNotFoundException("Link instalacyjny nie istnieje")
        val secret = provisioning.secretPlain
        if (provisioning.usedAt != null || secret == null || Instant.now().isAfter(provisioning.expiresAt)) {
            throw EntityNotFoundException("Link instalacyjny wygasł — wygeneruj nowy w Ustawieniach")
        }
        provisioning.usedAt = Instant.now()
        provisioning.secretPlain = null

        val appPassword = appPasswordRepository.findById(provisioning.appPasswordId)
            .orElseThrow { EntityNotFoundException("Konto zostało odwołane") }
        if (appPassword.revokedAt != null) {
            throw EntityNotFoundException("Konto zostało odwołane")
        }
        val user = userRepository.findById(appPassword.userId)
            .orElseThrow { EntityNotFoundException("Użytkownik nie istnieje") }

        return ProfilePayload(
            fileName = "detailboost-kontakty.mobileconfig",
            xml = MobileConfigBuilder.cardDavProfile(
                accountId = appPassword.id,
                hostName = hostName,
                principalPath = "/api/v1/carddav/${appPassword.studioId}/",
                username = user.email,
                password = secret,
            ),
        )
    }

    fun listAccounts(principal: UserPrincipal): List<CardDavAppPasswordEntity> =
        appPasswordRepository.findAllByUserId(principal.userId.value).filter { it.revokedAt == null }

    @Transactional
    fun revokeAccount(principal: UserPrincipal, accountId: UUID) {
        val account = appPasswordRepository.findByIdAndUserId(accountId, principal.userId.value)
            ?: throw EntityNotFoundException("Konto nie istnieje")
        account.revokedAt = Instant.now()
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    companion object {
        private const val LINK_TTL_MINUTES = 10L
        private const val TOKEN_BYTES = 32
        // 24 bajty → 32 znaki base64url; wpisywane tylko maszynowo, więc długość nie boli.
        private const val SECRET_BYTES = 24
    }
}
