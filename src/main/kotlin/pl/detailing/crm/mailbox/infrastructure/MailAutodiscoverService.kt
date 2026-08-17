package pl.detailing.crm.mailbox.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.shared.ValidationException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class MailProviderDetection(
    val providerType: MailProviderType,
    val authType: MailAuthType,
    val imapHost: String? = null,
    val imapPort: Int? = null,
    val smtpHost: String? = null,
    val smtpPort: Int? = null,
    val requiresAppPassword: Boolean = false,
    val guideUrl: String? = null
)

/**
 * Derives mailbox settings from the e-mail address alone (the Thunderbird approach), so the
 * onboarding form never asks a detailing studio owner for host names and port numbers.
 */
@Service
class MailAutodiscoverService {

    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun detect(email: String): MailProviderDetection {
        val domain = email.substringAfter('@', "").trim().lowercase()
        if (domain.isBlank() || !email.contains('@')) {
            throw ValidationException("Nieprawidłowy adres e-mail: $email")
        }

        oauthProviders[domain]?.let { return it }
        knownImapProviders[domain]?.let { return it }
        fetchFromIspdb(domain)?.let { return it }

        // Nothing known about this domain: the imap./smtp. convention is right often enough
        // that the user only has to correct the host instead of typing everything.
        return MailProviderDetection(
            providerType = MailProviderType.IMAP_SMTP,
            authType = MailAuthType.PASSWORD,
            imapHost = "imap.$domain",
            imapPort = 993,
            smtpHost = "smtp.$domain",
            smtpPort = 587
        )
    }

    private fun fetchFromIspdb(domain: String): MailProviderDetection? {
        val urls = listOf(
            "https://autoconfig.thunderbird.net/v1.1/$domain",
            "https://autoconfig.$domain/mail/config-v1.1.xml"
        )
        for (url in urls) {
            val body = fetch(url) ?: continue
            parseAutoconfig(body)?.let {
                log.debug("Autoconfig hit for domain {} via {}", domain, url)
                return it
            }
        }
        return null
    }

    private fun fetch(url: String): String? = try {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) response.body() else null
    } catch (ex: Exception) {
        // Autoconfig is best-effort: any network/TLS/timeout problem falls through to the next step.
        log.debug("Autoconfig lookup failed for {}: {}", url, ex.message)
        null
    }

    /** Internal for testability — parses a Mozilla autoconfig XML document. */
    internal fun parseAutoconfig(xml: String): MailProviderDetection? {
        val imap = extractServer(xml, "incoming", "imap") ?: return null
        val smtp = extractServer(xml, "outgoing", "smtp")
        return MailProviderDetection(
            providerType = MailProviderType.IMAP_SMTP,
            authType = MailAuthType.PASSWORD,
            imapHost = imap.first,
            imapPort = imap.second,
            smtpHost = smtp?.first,
            smtpPort = smtp?.second
        )
    }

    private fun extractServer(xml: String, direction: String, type: String): Pair<String, Int?>? {
        val blockRegex = Regex(
            "<(${direction})Server[^>]*type=\"$type\"[^>]*>(.*?)</\\1Server>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val block = blockRegex.find(xml)?.groupValues?.get(2) ?: return null
        val host = Regex("<hostname>\\s*(.*?)\\s*</hostname>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val port = Regex("<port>\\s*(\\d+)\\s*</port>").find(block)?.groupValues?.get(1)?.toIntOrNull()
        return host to port
    }

    private companion object {
        val oauthProviders: Map<String, MailProviderDetection> = listOf(
            "gmail.com", "googlemail.com"
        ).associateWith {
            MailProviderDetection(MailProviderType.GOOGLE_API, MailAuthType.OAUTH2)
        } + listOf(
            "outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.pl"
        ).associateWith {
            MailProviderDetection(MailProviderType.MS_GRAPH, MailAuthType.OAUTH2)
        }

        val knownImapProviders: Map<String, MailProviderDetection> = mapOf(
            "wp.pl" to imap("imap.wp.pl", 993, "smtp.wp.pl", 465),
            "o2.pl" to imap("poczta.o2.pl", 993, "poczta.o2.pl", 465),
            "onet.pl" to imap("imap.poczta.onet.pl", 993, "smtp.poczta.onet.pl", 465),
            "op.pl" to imap("imap.poczta.onet.pl", 993, "smtp.poczta.onet.pl", 465),
            "interia.pl" to imap("poczta.interia.pl", 993, "poczta.interia.pl", 465),
            "icloud.com" to MailProviderDetection(
                providerType = MailProviderType.IMAP_SMTP,
                authType = MailAuthType.APP_PASSWORD,
                imapHost = "imap.mail.me.com",
                imapPort = 993,
                smtpHost = "smtp.mail.me.com",
                smtpPort = 587,
                requiresAppPassword = true,
                guideUrl = "https://support.apple.com/pl-pl/102654"
            )
        )

        fun imap(imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int) = MailProviderDetection(
            providerType = MailProviderType.IMAP_SMTP,
            authType = MailAuthType.PASSWORD,
            imapHost = imapHost,
            imapPort = imapPort,
            smtpHost = smtpHost,
            smtpPort = smtpPort
        )
    }
}
