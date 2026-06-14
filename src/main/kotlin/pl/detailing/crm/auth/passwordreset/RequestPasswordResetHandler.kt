package pl.detailing.crm.auth.passwordreset

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.user.infrastructure.UserRepository

/**
 * Handles a "forgot password" request. To prevent account enumeration the caller
 * always receives the same response regardless of whether the account exists —
 * this handler does its work silently and returns Unit in every case.
 */
@Service
class RequestPasswordResetHandler(
    private val userRepository: UserRepository,
    private val tokenService: PasswordResetTokenService,
    private val emailProvider: EmailProvider,
    private val properties: PasswordResetProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(request: ForgotPasswordRequest): Unit = withContext(Dispatchers.IO) {
        val email = request.email.lowercase().trim()
        if (email.isBlank()) {
            return@withContext
        }

        val userEntity = userRepository.findByEmail(email)
        if (userEntity == null || !userEntity.isActive) {
            // Never disclose whether the address is registered or active.
            logger.info("Password reset requested for unknown/inactive account")
            return@withContext
        }

        if (!tokenService.tryStartCooldown(email)) {
            // A reset email was already sent very recently — skip to avoid flooding
            // the inbox. The controller still returns the generic success response.
            logger.info("Password reset re-requested within cooldown window [userId={}]", userEntity.id)
            return@withContext
        }

        val rawToken = tokenService.issueToken(userEntity.id)
        val resetLink = "${properties.frontendBaseUrl.trimEnd('/')}/reset-password?token=$rawToken"

        val result = emailProvider.send(
            to = userEntity.email,
            subject = "Resetowanie hasła – DetailBoost",
            bodyText = buildBody(userEntity.firstName, resetLink)
        )

        if (result.success) {
            logger.info("Password reset email dispatched [userId={}]", userEntity.id)
        } else {
            logger.warn(
                "Password reset email delivery failed [userId={}]: {}",
                userEntity.id, result.errorMessage
            )
        }
    }

    private fun buildBody(firstName: String, resetLink: String): String = """
        Cześć $firstName,

        Otrzymaliśmy prośbę o zresetowanie hasła do Twojego konta w DetailBoost.

        Aby ustawić nowe hasło, kliknij w poniższy link:
        $resetLink

        Link jest aktywny przez ${properties.tokenTtlMinutes} minut. Po tym czasie wygaśnie i trzeba będzie poprosić o reset ponownie.

        Jeśli to nie Ty prosiłeś(-aś) o zresetowanie hasła, zignoruj tę wiadomość — Twoje hasło pozostanie bez zmian.

        Pozdrawiamy,
        Zespół DetailBoost
    """.trimIndent()
}
