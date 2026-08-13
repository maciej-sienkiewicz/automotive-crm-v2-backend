package pl.detailing.crm.ksef.revenue.send

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.akmf.ksef.sdk.api.builders.session.OpenOnlineSessionRequestBuilder
import pl.akmf.ksef.sdk.api.builders.session.SendInvoiceOnlineSessionRequestBuilder
import pl.akmf.ksef.sdk.client.interfaces.CryptographyService
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.ApiException
import pl.akmf.ksef.sdk.client.model.UpoVersion
import pl.akmf.ksef.sdk.client.model.session.FormCode
import pl.akmf.ksef.sdk.client.model.session.SchemaVersion
import pl.akmf.ksef.sdk.client.model.session.SessionValue
import pl.akmf.ksef.sdk.client.model.session.SystemCode
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.shared.StudioId
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Wynik próby wysyłki faktury do KSeF.
 *
 * Rozróżnienie [Rejected] od [TransientFailure] jest krytyczne:
 * - Rejected: KSeF odrzucił dokument (walidacja) — ponowna wysyłka tego samego XML
 *   nie ma sensu; wymagana poprawa danych przez użytkownika.
 * - TransientFailure: problem łączności / niedostępność KSeF / limit żądań — faktura
 *   trafia do kolejki retry (tryb offline24) i jest dosyłana automatycznie.
 */
sealed interface KsefSendOutcome {
    /** Faktura przyjęta — nadany numer KSeF. UPO może dojść później (null → dociągnie scheduler). */
    data class Accepted(val ksefNumber: String, val upoXml: String?, val invoiceHash: String?) : KsefSendOutcome

    /** Faktura przyjęta do sesji (są numery referencyjne), ale KSeF jeszcze przetwarza. */
    data class Submitted(val sessionReference: String, val invoiceReference: String, val invoiceHash: String) :
        KsefSendOutcome

    /** Odrzucona przez walidację KSeF — błąd trwały. */
    data class Rejected(val error: String) : KsefSendOutcome

    /** Błąd przejściowy — do ponowienia. */
    data class TransientFailure(val error: String) : KsefSendOutcome
}

/** Wynik odpytania o status faktury już przyjętej do sesji. */
sealed interface KsefStatusOutcome {
    data class Accepted(val ksefNumber: String, val upoXml: String?, val invoiceHash: String?) : KsefStatusOutcome
    object StillProcessing : KsefStatusOutcome
    data class Rejected(val error: String) : KsefStatusOutcome
    data class TransientFailure(val error: String) : KsefStatusOutcome
}

/**
 * Wysyłka faktur ustrukturyzowanych do KSeF w sesji interaktywnej (online).
 *
 * Przebieg pojedynczej wysyłki:
 * 1. Uwierzytelnienie kontekstu NIP studia (współdzielony [KsefAuthService] z modułem kosztowym).
 * 2. Otwarcie sesji online dla FA(3) z szyfrowaniem AES-256 (klucz zaszyfrowany kluczem publicznym KSeF).
 * 3. Wysyłka zaszyfrowanego XML + hashy (SHA-256 oryginału i szyfrogramu).
 * 4. Zamknięcie sesji i polling statusu faktury do przyjęcia (numer KSeF) lub odrzucenia.
 * 5. Pobranie UPO faktury.
 *
 * Sesja per faktura — wolumen studia detailingowego (kilka faktur dziennie) nie
 * uzasadnia sesji wsadowych, a krótkie sesje minimalizują stany wiszące.
 */
@Service
class KsefInvoiceSender(
    private val ksefClient: KSeFClient,
    private val cryptographyService: CryptographyService,
    private val authService: KsefAuthService
) {
    private val log = LoggerFactory.getLogger(KsefInvoiceSender::class.java)

    companion object {
        private const val STATUS_POLL_MAX_ATTEMPTS = 15
        private const val STATUS_POLL_DELAY_MS = 2_000L

        /** Kody statusu przetwarzania faktury w sesji KSeF. */
        private const val INVOICE_ACCEPTED_CODE = 200

        /** Kody ApiException traktowane jako błąd trwały (walidacja żądania/dokumentu). */
        private val PERMANENT_HTTP_CODES = 400..422
    }

    /**
     * Wysyła XML faktury do KSeF. Nie modyfikuje encji — decyzję o przejściu stanów
     * podejmuje wołający na podstawie zwróconego [KsefSendOutcome].
     */
    fun send(studioId: StudioId, invoiceXml: String): KsefSendOutcome {
        val accessToken = try {
            authService.getValidAccessToken(studioId)
        } catch (e: Exception) {
            return KsefSendOutcome.TransientFailure("Uwierzytelnienie KSeF nieudane: ${e.message}")
        }

        val sessionReference: String
        val invoiceReference: String
        val invoiceHash: String
        try {
            val encryptionData = cryptographyService.getEncryptionData()

            val openRequest = OpenOnlineSessionRequestBuilder()
                .withFormCode(FormCode(SystemCode.FA_3, SchemaVersion.VERSION_1_0E, SessionValue.FA))
                .withEncryptionInfo(encryptionData.encryptionInfo())
                .build()
            sessionReference = ksefClient.openOnlineSession(openRequest, UpoVersion.UPO_4_3, accessToken)
                .referenceNumber
            log.info("KSeF online session opened: studio={} session={}", studioId, sessionReference)

            val invoiceBytes = invoiceXml.toByteArray(StandardCharsets.UTF_8)
            val encryptedBytes = cryptographyService.encryptBytesWithAES256(
                invoiceBytes, encryptionData.cipherKey(), encryptionData.cipherIv()
            )
            val plainMeta = cryptographyService.getMetaData(invoiceBytes)
            val encryptedMeta = cryptographyService.getMetaData(encryptedBytes)
            invoiceHash = plainMeta.hashSHA

            val sendRequest = SendInvoiceOnlineSessionRequestBuilder()
                .withInvoiceHash(plainMeta.hashSHA)
                .withInvoiceSize(plainMeta.fileSize)
                .withEncryptedInvoiceHash(encryptedMeta.hashSHA)
                .withEncryptedInvoiceSize(encryptedMeta.fileSize)
                .withEncryptedInvoiceContent(Base64.getEncoder().encodeToString(encryptedBytes))
                .build()

            invoiceReference = ksefClient.onlineSessionSendInvoice(sessionReference, sendRequest, accessToken)
                .referenceNumber
            log.info(
                "KSeF invoice submitted: studio={} session={} invoiceRef={}",
                studioId, sessionReference, invoiceReference
            )
        } catch (e: ApiException) {
            return if (e.code in PERMANENT_HTTP_CODES) {
                KsefSendOutcome.Rejected(describe(e))
            } else {
                KsefSendOutcome.TransientFailure(describe(e))
            }
        } catch (e: Exception) {
            return KsefSendOutcome.TransientFailure("Błąd wysyłki do KSeF: ${e.message}")
        }

        // Sesję zamykamy od razu po wysyłce — przetwarzanie faktury toczy się dalej
        // po stronie KSeF; brak zamknięcia zostawia wiszące sesje w kontekście NIP.
        runCatching { ksefClient.closeOnlineSession(sessionReference, accessToken) }
            .onFailure { log.warn("KSeF closeOnlineSession failed (nieblokujące): {}", it.message) }

        return when (val status = pollInvoiceStatus(sessionReference, invoiceReference, accessToken)) {
            is KsefStatusOutcome.Accepted ->
                KsefSendOutcome.Accepted(status.ksefNumber, status.upoXml, status.invoiceHash ?: invoiceHash)
            is KsefStatusOutcome.Rejected -> KsefSendOutcome.Rejected(status.error)
            // Numery referencyjne zapisane — scheduler dokończy polling
            is KsefStatusOutcome.StillProcessing,
            is KsefStatusOutcome.TransientFailure ->
                KsefSendOutcome.Submitted(sessionReference, invoiceReference, invoiceHash)
        }
    }

    /**
     * Odpytuje status faktury przyjętej wcześniej do sesji (statusy SUBMITTED) —
     * używane bezpośrednio po wysyłce oraz przez scheduler po restarcie aplikacji.
     */
    fun checkStatus(studioId: StudioId, sessionReference: String, invoiceReference: String): KsefStatusOutcome {
        val accessToken = try {
            authService.getValidAccessToken(studioId)
        } catch (e: Exception) {
            return KsefStatusOutcome.TransientFailure("Uwierzytelnienie KSeF nieudane: ${e.message}")
        }
        return checkStatusOnce(sessionReference, invoiceReference, accessToken)
    }

    /** Pobiera UPO (XML) faktury po numerze KSeF. Null gdy UPO nie jest jeszcze dostępne. */
    fun fetchUpo(studioId: StudioId, sessionReference: String, ksefNumber: String): String? = try {
        val accessToken = authService.getValidAccessToken(studioId)
        val upo = ksefClient.getSessionInvoiceUpoByKsefNumber(sessionReference, ksefNumber, accessToken)
        String(upo, StandardCharsets.UTF_8)
    } catch (e: Exception) {
        log.info("UPO jeszcze niedostępne dla {}: {}", ksefNumber, e.message)
        null
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun pollInvoiceStatus(
        sessionReference: String,
        invoiceReference: String,
        accessToken: String
    ): KsefStatusOutcome {
        repeat(STATUS_POLL_MAX_ATTEMPTS) {
            Thread.sleep(STATUS_POLL_DELAY_MS)
            val outcome = checkStatusOnce(sessionReference, invoiceReference, accessToken)
            if (outcome !is KsefStatusOutcome.StillProcessing) return outcome
        }
        return KsefStatusOutcome.StillProcessing
    }

    private fun checkStatusOnce(
        sessionReference: String,
        invoiceReference: String,
        accessToken: String
    ): KsefStatusOutcome = try {
        val status = ksefClient.getSessionInvoiceStatus(sessionReference, invoiceReference, accessToken)
        val code = status.status?.code ?: 0
        when {
            code == INVOICE_ACCEPTED_CODE && !status.ksefNumber.isNullOrBlank() -> {
                val upo = runCatching {
                    String(
                        ksefClient.getSessionInvoiceUpoByKsefNumber(sessionReference, status.ksefNumber, accessToken),
                        StandardCharsets.UTF_8
                    )
                }.getOrNull()
                KsefStatusOutcome.Accepted(status.ksefNumber, upo, status.invoiceHash)
            }

            code >= 400 -> KsefStatusOutcome.Rejected(
                "KSeF odrzucił fakturę (kod $code): ${status.status?.description}" +
                    (status.status?.details?.takeIf { it.isNotEmpty() }?.joinToString(prefix = " — ") ?: "")
            )

            else -> KsefStatusOutcome.StillProcessing
        }
    } catch (e: ApiException) {
        KsefStatusOutcome.TransientFailure(describe(e))
    } catch (e: Exception) {
        KsefStatusOutcome.TransientFailure("Błąd sprawdzania statusu: ${e.message}")
    }

    private fun describe(e: ApiException): String =
        "KSeF HTTP ${e.code}: ${e.message?.take(1500)}"
}
