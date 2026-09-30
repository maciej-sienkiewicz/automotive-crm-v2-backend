package pl.detailing.crm.employee.leaverequest.session

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import java.time.Duration
import java.util.UUID

/** Sesja podpisu wydawana klientowi: skrót dokładnie tych bajtów PDF i jednorazowy token. */
data class SigningSession(val documentSha256: String, val challenge: String)

/**
 * Jednorazowe tokeny sesji podpisu wniosku (WYSIWYS i ochrona przed powtórzeniem —
 * ta sama maszyneria, co podpis na tablecie: [DocumentIntegrityService]).
 *
 * Klucz tokenu jest wyprowadzany z wniosku i ROLI podpisującego, a przy decyzji także
 * z osoby rozpatrującej. Gdyby wszyscy dzielili jeden klucz, drugi kierownik otwierający
 * ten sam wniosek unieważniałby sesję pierwszego, który akurat rysuje podpis — a przegrany
 * dowiadywałby się o tym dopiero po kliknięciu „Podpisz". Rozstrzyganie, kto decyduje
 * pierwszy, należy do warunkowego UPDATE-u, nie do tokenu.
 */
@Service
class LeaveSigningSessions(private val integrity: DocumentIntegrityService) {

    companion object {
        /**
         * Pracownik czyta wniosek przed podpisem, rozpatrujący sprawdza obsadę — 30 minut
         * starcza na jedno i drugie, a wygasły token odnawia `…/signing-session`
         * i `…/decision-session` bez zmiany dokumentu.
         */
        val TTL: Duration = Duration.ofMinutes(30)

        const val STALE_SESSION =
            "Sesja podpisu wygasła albo została już użyta. Otwórz dokument ponownie i podpisz jeszcze raz."
        const val DOCUMENT_CHANGED = "Dokument został odświeżony. Sprawdź go i podpisz ponownie."
    }

    fun issueEmployee(requestId: UUID, documentSha256: String): SigningSession =
        SigningSession(documentSha256, integrity.issueChallenge(employeeKey(requestId), TTL))

    fun issueDecision(requestId: UUID, approver: UserId, documentSha256: String): SigningSession =
        SigningSession(documentSha256, integrity.issueChallenge(decisionKey(requestId, approver), TTL))

    /** Zużywa token pracownika atomowo; drugi raz ten sam token nie przejdzie (409). */
    fun consumeEmployee(requestId: UUID, challenge: String) {
        if (!integrity.consumeChallenge(employeeKey(requestId), challenge)) throw ConflictException(STALE_SESSION)
    }

    fun consumeDecision(requestId: UUID, approver: UserId, challenge: String) {
        if (!integrity.consumeChallenge(decisionKey(requestId, approver), challenge)) throw ConflictException(STALE_SESSION)
    }

    /**
     * Skrót od klienta = skrót zapisany przy utworzeniu wersji = skrót bajtów pobranych
     * ponownie z magazynu. Porównania stałoczasowe; każda niezgodność to 409 i ponowny podpis.
     */
    fun verifyDocument(clientSha256: String, storedSha256: String, storageBytes: ByteArray) {
        val storageSha256 = integrity.sha256Hex(storageBytes)
        if (!integrity.digestsMatch(storedSha256, clientSha256) || !integrity.digestsMatch(storedSha256, storageSha256)) {
            throw ConflictException(DOCUMENT_CHANGED)
        }
    }

    private fun employeeKey(requestId: UUID): UUID =
        UUID.nameUUIDFromBytes("leave-request:employee:$requestId".toByteArray())

    private fun decisionKey(requestId: UUID, approver: UserId): UUID =
        UUID.nameUUIDFromBytes("leave-request:decision:$requestId:${approver.value}".toByteArray())
}
