package pl.detailing.crm.gus.adapter.bir

import org.slf4j.LoggerFactory
import pl.detailing.crm.gus.adapter.bir.soap.GusRawSoapClient
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Zarządza cyklem życia sesji GUS BIR.
 *
 * GUS przydziela sesję na ~60 minut per klucz API.
 * Wszystkie zapytania (niezależnie od studia/klienta) współdzielą jedną sesję,
 * ponieważ sesja jest powiązana z kluczem API, a nie z użytkownikiem.
 *
 * Wątkobezpieczeństwo: ReentrantLock gwarantuje, że tylko jeden wątek
 * przeprowadza ponowne logowanie w danym momencie.
 */
class GusSessionManager(
    private val soapClient: GusRawSoapClient,
    private val apiKey: String,
    private val sessionTtlMinutes: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    @Volatile private var sessionId: String? = null
    @Volatile private var sessionCreatedAt: Instant? = null

    /**
     * Zwraca aktywny identyfikator sesji.
     * Jeśli sesja wygasła lub nie istnieje, loguje się ponownie.
     */
    fun getActiveSessionId(): String = lock.withLock {
        val current = sessionId
        val createdAt = sessionCreatedAt
        if (current != null && createdAt != null && !isExpired(createdAt)) {
            return current
        }
        return refresh()
    }

    /**
     * Unieważnia bieżącą sesję (wywoływane po błędzie sesji zwróconym przez GUS).
     * Następne wywołanie [getActiveSessionId] wymusi nowe logowanie.
     */
    fun invalidate() = lock.withLock {
        log.warn("GUS session invalidated – next call will re-authenticate")
        sessionId = null
        sessionCreatedAt = null
    }

    private fun refresh(): String {
        log.info("Establishing new GUS BIR session")
        val newId = soapClient.login(apiKey)
        if (newId.isBlank()) {
            throw GusServiceUnavailableException(
                "GUS zwrócił pusty identyfikator sesji – sprawdź poprawność klucza API"
            )
        }
        sessionId = newId
        sessionCreatedAt = Instant.now()
        log.info("GUS BIR session established (ttl={}min)", sessionTtlMinutes)
        return newId
    }

    private fun isExpired(createdAt: Instant): Boolean =
        Instant.now().isAfter(createdAt.plusSeconds(sessionTtlMinutes * 60))
}
