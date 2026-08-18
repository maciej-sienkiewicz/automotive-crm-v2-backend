package pl.detailing.crm.mailbox.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.shared.ValidationException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Hashtable
import javax.naming.directory.InitialDirContext
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Outcome of probing a host for a real, correctly-named IMAPS endpoint.
 *
 * A bare TCP-connect success is not enough evidence: shared hosting often answers on 993
 * with a TLS certificate that covers the provider's own box name, not the client's domain
 * (e.g. carslab.pl's MX is mail.carslab.pl, but the certificate on that IP is issued for
 * s190.cyber-folks.pl). CERTIFICATE_MISMATCH lets the caller fall back to the box's reverse
 * DNS name, which is what the certificate actually covers.
 */
internal enum class MailHostProbeResult { VERIFIED, CERTIFICATE_MISMATCH, UNREACHABLE }

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
 *
 * A custom domain (kontakt@firma.pl hosted at home.pl/nazwa.pl/OVH) is never itself in the
 * ISPDB — the essential step is the MX lookup: the MX record points at the actual hosting
 * provider, and it is the provider's base domain that the ISPDB knows. Only when every lookup
 * fails do we fall back to the imap.{domain} convention, and even then the guess is verified
 * with a TCP probe instead of being returned blindly.
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

        fetchFromIspdb(domain)?.let { return substitutePlaceholders(it, email) }
        srvLookup(domain)?.let { return it }

        val mxHosts = mxLookup(domain)
        if (mxHosts.isNotEmpty()) {
            log.debug("MX for {} -> {}", domain, mxHosts)

            // Providers like home.pl give every account its own mail host (pocztaNNNNNNN.home.pl)
            // and the MX points straight at it — the MX host itself IS the IMAP/SMTP server.
            // No static name can express that, so a verified MX host is the most specific
            // result available and wins.
            for (mx in mxHosts) {
                verifiedMailHost(mx)?.let { host ->
                    log.debug("MX host {} resolves to a verified mail server {} for {}", mx, host, domain)
                    return imapDetection(host, IMAPS_PORT, host, 465)
                }
            }

            // Hosters whose MX is not the mail server (OVH relays) — works even when the ISPDB is down.
            for (mx in mxHosts) {
                knownMxSuffixes.entries.firstOrNull { (suffix, _) ->
                    mx == suffix.removePrefix(".") || mx.endsWith(suffix)
                }?.let { return it.value }
            }

            // Thunderbird's key step: ask the ISPDB about the MX host's base domain.
            for (candidate in mxBaseDomainCandidates(mxHosts, domain)) {
                fetchFromIspdb(candidate)?.let {
                    log.debug("Autoconfig hit for {} via MX base domain {}", domain, candidate)
                    return substitutePlaceholders(it, email)
                }
            }
        }

        // Convention guess, but verified: only a host with a matching TLS certificate is
        // worth pre-filling.
        probedGuess(domain, mxHosts)?.let { return it }

        // Absolute last resort — a seed for the manual-configuration form.
        return imapDetection("imap.$domain", 993, "smtp.$domain", 587)
    }

    // ── ISPDB / autoconfig ──────────────────────────────────────────────────

    protected open fun fetchFromIspdb(domain: String): MailProviderDetection? {
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

    /** ISPDB configs use %EMAILDOMAIN%-style placeholders in hostnames. */
    internal fun substitutePlaceholders(detection: MailProviderDetection, email: String): MailProviderDetection {
        val domain = email.substringAfter('@').lowercase()
        val localPart = email.substringBefore('@')
        fun swap(host: String?): String? = host
            ?.replace("%EMAILDOMAIN%", domain)
            ?.replace("%EMAILADDRESS%", email)
            ?.replace("%EMAILLOCALPART%", localPart)
        return detection.copy(imapHost = swap(detection.imapHost), smtpHost = swap(detection.smtpHost))
    }

    // ── DNS (SRV per RFC 6186, MX) ──────────────────────────────────────────

    /** Raw record strings for the given name/type; empty on any DNS failure. */
    protected open fun dnsRecords(name: String, type: String): List<String> = try {
        val env = Hashtable<String, String>().apply {
            put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
            put("com.sun.jndi.dns.timeout.initial", "2000")
            put("com.sun.jndi.dns.timeout.retries", "1")
        }
        val attribute = InitialDirContext(env).getAttributes(name, arrayOf(type)).get(type)
        if (attribute == null) emptyList()
        else (0 until attribute.size()).map { attribute.get(it).toString() }
    } catch (ex: Exception) {
        log.debug("DNS {} lookup failed for {}: {}", type, name, ex.message)
        emptyList()
    }

    private fun srvLookup(domain: String): MailProviderDetection? {
        // Hosting panels often publish template SRV records pointing at the bare domain,
        // whose A record is the client's WWW server, not a mail server. A record is only
        // trusted when its target actually answers on the advertised port — otherwise the
        // cascade must fall through to the MX lookup.
        val imap = parseSrvRecords(dnsRecords("_imaps._tcp.$domain", "SRV"))
            .firstOrNull { (host, port) ->
                tcpReachable(host, port).also { reachable ->
                    if (!reachable) log.debug("SRV target {}:{} for {} does not answer — ignoring record", host, port, domain)
                }
            } ?: return null
        val smtp = parseSrvRecords(dnsRecords("_submission._tcp.$domain", "SRV")).firstOrNull()
        log.debug("SRV hit for {}: imaps={}", domain, imap)
        return imapDetection(imap.first, imap.second, smtp?.first ?: imap.first, smtp?.second ?: 587)
    }

    /** MX hostnames sorted by priority, lowercase, without trailing dots. */
    private fun mxLookup(domain: String): List<String> = parseMxRecords(dnsRecords(domain, "MX"))

    // ── Verified convention guess ───────────────────────────────────────────

    private fun probedGuess(domain: String, mxHosts: List<String>): MailProviderDetection? {
        val candidates = (listOf("imap.$domain", "mail.$domain", "poczta.$domain") + mxHosts)
            .distinct()
            .take(MAX_PROBED_CANDIDATES)
        val verified = candidates.firstNotNullOfOrNull { verifiedMailHost(it) } ?: return null
        log.debug("Probe hit for {}: {}", domain, verified)
        val smtpHost = if (verified.startsWith("imap.")) verified.replaceFirst("imap.", "smtp.") else verified
        return imapDetection(verified, IMAPS_PORT, smtpHost, 587)
    }

    protected open fun tcpReachable(host: String, port: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (ex: Exception) {
        false
    }

    /**
     * Verifies that [host] is a real IMAPS endpoint whose certificate actually covers it —
     * a raw TCP-open port proves nothing on shared hosting. On a certificate/hostname
     * mismatch, falls back to the reverse-DNS name of the same IP, which is what the box's
     * own certificate is issued for.
     */
    private fun verifiedMailHost(host: String, port: Int = IMAPS_PORT): String? =
        when (probeMailHost(host, port)) {
            MailHostProbeResult.VERIFIED -> host
            MailHostProbeResult.CERTIFICATE_MISMATCH -> {
                val ptrHost = reverseDnsHostname(host)
                if (ptrHost != null && ptrHost != host && probeMailHost(ptrHost, port) == MailHostProbeResult.VERIFIED) {
                    log.debug("Certificate on {} does not cover it — using its reverse-DNS name {} instead", host, ptrHost)
                    ptrHost
                } else {
                    null
                }
            }
            MailHostProbeResult.UNREACHABLE -> null
        }

    /** Full TLS handshake with hostname verification — not just a TCP connect. */
    internal open fun probeMailHost(host: String, port: Int): MailHostProbeResult = try {
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            plain.soTimeout = PROBE_TIMEOUT_MS
            val sslSocket = SSLContext.getDefault().socketFactory
                .createSocket(plain, host, port, true) as SSLSocket
            sslSocket.use {
                val params = it.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                it.sslParameters = params
                it.startHandshake()
            }
        }
        MailHostProbeResult.VERIFIED
    } catch (ex: SSLPeerUnverifiedException) {
        MailHostProbeResult.CERTIFICATE_MISMATCH
    } catch (ex: SSLHandshakeException) {
        if (isCertificateHostnameMismatch(ex)) MailHostProbeResult.CERTIFICATE_MISMATCH else MailHostProbeResult.UNREACHABLE
    } catch (ex: Exception) {
        MailHostProbeResult.UNREACHABLE
    }

    private fun isCertificateHostnameMismatch(ex: SSLHandshakeException): Boolean {
        val message = (ex.message.orEmpty() + " " + ex.cause?.message.orEmpty())
        return HOSTNAME_MISMATCH_MARKERS.any { message.contains(it, ignoreCase = true) }
    }

    /** Reverse DNS of a hostname's first A record — the name the box's own certificate covers. */
    private fun reverseDnsHostname(host: String): String? {
        val ip = dnsRecords(host, "A").firstOrNull()?.trim() ?: return null
        val octets = ip.split('.')
        if (octets.size != 4) return null
        val ptrQuery = octets.asReversed().joinToString(".") + ".in-addr.arpa"
        return dnsRecords(ptrQuery, "PTR").firstOrNull()?.trimEnd('.')?.lowercase()?.ifBlank { null }
    }

    companion object {
        private const val IMAPS_PORT = 993
        private const val PROBE_TIMEOUT_MS = 1500
        private const val MAX_PROBED_CANDIDATES = 5
        private val HOSTNAME_MISMATCH_MARKERS = listOf(
            "No subject alternative", "No name matching", "doesn't match", "hostname"
        )

        /** "10 mx.home.pl." → hosts sorted by priority. */
        internal fun parseMxRecords(records: List<String>): List<String> = records
            .mapNotNull { record ->
                val parts = record.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val priority = parts[0].toIntOrNull() ?: return@mapNotNull null
                priority to parts[1].trimEnd('.').lowercase()
            }
            .sortedBy { it.first }
            .map { it.second }
            .filter { it.isNotBlank() }

        /** "0 1 993 imap.megiteam.pl." → (host, port) sorted by priority. */
        internal fun parseSrvRecords(records: List<String>): List<Pair<String, Int>> = records
            .mapNotNull { record ->
                val parts = record.trim().split(Regex("\\s+"))
                if (parts.size < 4) return@mapNotNull null
                val priority = parts[0].toIntOrNull() ?: return@mapNotNull null
                val port = parts[2].toIntOrNull() ?: return@mapNotNull null
                val host = parts[3].trimEnd('.').lowercase()
                // RFC 6186: a single record with target "." means the service is explicitly absent.
                if (host.isBlank() || host == ".") return@mapNotNull null
                Triple(priority, host, port)
            }
            .sortedBy { it.first }
            .map { it.second to it.third }

        /**
         * Base-domain candidates of the hosting provider, derived from the MX hosts:
         * mx.home.pl → [home.pl, mx.home.pl]. The two-label suffix is tried first (that is
         * what the ISPDB indexes); a miss there is a fast 404. The client's own domain is
         * excluded — it was already looked up directly.
         */
        internal fun mxBaseDomainCandidates(mxHosts: List<String>, ownDomain: String): List<String> {
            val candidates = linkedSetOf<String>()
            for (mx in mxHosts) {
                val labels = mx.split('.').filter { it.isNotBlank() }
                if (labels.size >= 2) candidates.add(labels.takeLast(2).joinToString("."))
                if (labels.size >= 3) candidates.add(labels.takeLast(3).joinToString("."))
            }
            candidates.remove(ownDomain)
            return candidates.toList()
        }

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
            "wp.pl" to imapDetection("imap.wp.pl", 993, "smtp.wp.pl", 465),
            "o2.pl" to imapDetection("poczta.o2.pl", 993, "poczta.o2.pl", 465),
            "onet.pl" to imapDetection("imap.poczta.onet.pl", 993, "smtp.poczta.onet.pl", 465),
            "op.pl" to imapDetection("imap.poczta.onet.pl", 993, "smtp.poczta.onet.pl", 465),
            "interia.pl" to imapDetection("poczta.interia.pl", 993, "poczta.interia.pl", 465),
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

        /**
         * Custom domains hosted at the big providers, recognized by the MX target. This is an
         * offline fast path in front of the MX→ISPDB lookup, not a replacement for it.
         */
        val knownMxSuffixes: Map<String, MailProviderDetection> = mapOf(
            ".ovh.net" to imapDetection("ssl0.ovh.net", 993, "ssl0.ovh.net", 465)
        )

        fun imapDetection(imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int) = MailProviderDetection(
            providerType = MailProviderType.IMAP_SMTP,
            authType = MailAuthType.PASSWORD,
            imapHost = imapHost,
            imapPort = imapPort,
            smtpHost = smtpHost,
            smtpPort = smtpPort
        )
    }
}
