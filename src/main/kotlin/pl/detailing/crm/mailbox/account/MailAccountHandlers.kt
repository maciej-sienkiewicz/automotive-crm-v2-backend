package pl.detailing.crm.mailbox.account

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.ImapSyncService
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.mailbox.infrastructure.MailAutodiscoverService
import pl.detailing.crm.mailbox.infrastructure.MailProviderDetection
import pl.detailing.crm.mailbox.infrastructure.MailboxEncryptionService
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.Properties
import java.util.UUID

data class DetectMailProviderQuery(val email: String)

data class ConnectMailAccountCommand(
    val studioId: StudioId,
    val email: String,
    val password: String,
    val imapHost: String? = null,
    val imapPort: Int? = null,
    val smtpHost: String? = null,
    val smtpPort: Int? = null
)

/**
 * Mailbox onboarding. The owner types an address; everything else — provider, hosts, ports,
 * whether an app password is required — is resolved for them, because studio owners are not
 * expected to know what IMAP is.
 */
@Service
class MailAccountService(
    private val accountRepository: MailAccountRepository,
    private val autodiscoverService: MailAutodiscoverService,
    private val encryptionService: MailboxEncryptionService,
    private val imapSyncService: ImapSyncService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun detect(query: DetectMailProviderQuery): MailProviderDetection =
        autodiscoverService.detect(query.email.trim())

    @Transactional
    suspend fun connect(command: ConnectMailAccountCommand): MailAccountEntity = withContext(Dispatchers.IO) {
        val email = command.email.trim().lowercase()
        if (!email.contains('@')) throw ValidationException("Podaj poprawny adres e-mail")

        val detection = autodiscoverService.detect(email)
        if (detection.providerType != MailProviderType.IMAP_SMTP && command.imapHost == null) {
            throw ValidationException(
                "Tę skrzynkę podłączamy przez logowanie u dostawcy — użyj przycisku logowania zamiast hasła"
            )
        }

        val imapHost = command.imapHost ?: detection.imapHost
            ?: throw ValidationException("Nie udało się wykryć serwera poczty — uzupełnij dane ręcznie")
        val imapPort = command.imapPort ?: detection.imapPort ?: DEFAULT_IMAPS_PORT
        val smtpHost = command.smtpHost ?: detection.smtpHost ?: imapHost.replaceFirst("imap", "smtp")
        val smtpPort = command.smtpPort ?: detection.smtpPort ?: DEFAULT_SUBMISSION_PORT

        verifyCredentials(imapHost, imapPort, email, command.password)

        // Encrypting is the first moment the key is needed; a missing key is an operator
        // misconfiguration and must surface as a clear message, not a bare 500.
        val encryptedPassword = try {
            encryptionService.encrypt(command.password)
        } catch (e: IllegalStateException) {
            throw ValidationException(e.message ?: "Brak klucza szyfrowania skrzynek pocztowych")
        }

        val existing = accountRepository.findByStudioIdAndEmailAddress(command.studioId.value, email)
        val account = existing?.apply {
            this.encryptedPassword = encryptedPassword
            this.imapHost = imapHost
            this.imapPort = imapPort
            this.smtpHost = smtpHost
            this.smtpPort = smtpPort
            status = MailAccountStatus.ACTIVE
            lastError = null
            updatedAt = Instant.now()
        } ?: MailAccountEntity(
            id = UUID.randomUUID(),
            studioId = command.studioId.value,
            emailAddress = email,
            providerType = MailProviderType.IMAP_SMTP,
            authType = if (detection.requiresAppPassword) MailAuthType.APP_PASSWORD else MailAuthType.PASSWORD,
            encryptedPassword = encryptedPassword,
            imapHost = imapHost,
            imapPort = imapPort,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            status = MailAccountStatus.ACTIVE,
            lastError = null,
            lastSyncAt = null,
            inboxUidValidity = null,
            inboxLastUid = null,
            sentUidValidity = null,
            sentLastUid = null
        )

        val saved = accountRepository.save(account)
        log.info("[MAILBOX] Mailbox {} connected for studio {}", email, command.studioId)
        saved
    }

    fun list(studioId: StudioId): List<MailAccountEntity> = accountRepository.findByStudioId(studioId.value)

    @Transactional
    fun disconnect(studioId: StudioId, accountId: UUID) {
        val account = accountRepository.findByIdAndStudioId(accountId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono skrzynki")
        account.status = MailAccountStatus.DISABLED
        account.encryptedPassword = null
        account.updatedAt = Instant.now()
        accountRepository.save(account)
        log.info("[MAILBOX] Mailbox {} disconnected for studio {}", account.emailAddress, studioId)
    }

    suspend fun syncNow(studioId: StudioId, accountId: UUID) = withContext(Dispatchers.IO) {
        val account = accountRepository.findByIdAndStudioId(accountId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono skrzynki")
        imapSyncService.syncAccount(account)
    }

    /**
     * Logging in before saving turns a silent "no mail ever arrives" into an actionable error,
     * and lets us recognise the app-password case instead of showing "wrong password".
     */
    private fun verifyCredentials(host: String, port: Int, email: String, password: String) {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "15000")
        }
        try {
            val store = Session.getInstance(props).getStore("imaps")
            store.connect(host, email, password)
            store.close()
        } catch (e: AuthenticationFailedException) {
            val serverMessage = e.message.orEmpty().lowercase()
            if (APP_PASSWORD_HINTS.any { serverMessage.contains(it) }) {
                throw ValidationException(
                    "Ta skrzynka wymaga hasła aplikacji — wygeneruj je w panelu dostawcy poczty i wklej zamiast zwykłego hasła"
                )
            }
            throw ValidationException("Serwer poczty odrzucił adres lub hasło — sprawdź dane i spróbuj ponownie")
        } catch (e: Exception) {
            log.debug("[MAILBOX] IMAP verification failed for {}: {}", email, e.message)
            throw ValidationException("Nie udało się połączyć z serwerem $host — sprawdź adres lub uzupełnij dane ręcznie")
        }
    }

    companion object {
        private const val DEFAULT_IMAPS_PORT = 993
        private const val DEFAULT_SUBMISSION_PORT = 587
        private val APP_PASSWORD_HINTS = listOf("application-specific password", "app password", "app-specific")
    }
}
