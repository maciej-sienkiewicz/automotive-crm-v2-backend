package pl.detailing.crm.service.update

import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

data class UpdateServiceRequest(
    val originalServiceId: String,
    val name: String,
    val basePriceNet: Long,
    // Exact gross as entered by the user; null (older clients) = derive from net.
    val basePriceGross: Long? = null,
    val vatRate: Int,
    val requireManualPrice: Boolean = false
) {
    /**
     * `originalServiceId` jako usługa z cennika - albo czytelna odmowa.
     *
     * Frontend zakłada usługi „w locie" z identyfikatorem zastępczym (`temp-<ms>`),
     * które nigdy nie trafiły do bazy. Gdy taki identyfikator przyszedł tutaj,
     * `ServiceId.fromString` rzucał gołe `IllegalArgumentException("Invalid UUID
     * string: temp-…")`: klient dostawał anonimowe „Żądanie zawiera nieprawidłowe
     * dane", a log nie mówił, które pole i który endpoint - trzeba było zgadywać
     * po samej wartości. Odmowa nazywa teraz pole i powód.
     */
    fun originalServiceIdOrReject(): ServiceId {
        val uuid = runCatching { UUID.fromString(originalServiceId) }.getOrNull()
            ?: throw ValidationException(
                "Usługa '$originalServiceId' nie jest zapisana w cenniku (originalServiceId nie jest UUID) - " +
                    "zmień jej cenę na pozycji wyceny zamiast w cenniku"
            )
        return ServiceId(uuid)
    }
}
