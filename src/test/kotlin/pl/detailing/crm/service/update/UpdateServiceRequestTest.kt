package pl.detailing.crm.service.update

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Regresja z produkcji: szybka rezerwacja w kalendarzu wysyłała do
 * `POST /services/update` usługę założoną w locie, z identyfikatorem zastępczym
 * `temp-<ms>`. `ServiceId.fromString` rzucał gołe `IllegalArgumentException`,
 * klient dostawał anonimowe 400, a log - samo „Invalid UUID string".
 */
class UpdateServiceRequestTest {

    private fun request(originalServiceId: String) = UpdateServiceRequest(
        originalServiceId = originalServiceId,
        name = "powłoka na felgi",
        basePriceNet = 73_171,
        basePriceGross = 90_000,
        vatRate = 23
    )

    @Test
    fun `usługa z cennika przechodzi jako ServiceId`() {
        val id = UUID.randomUUID()

        assertThat(request(id.toString()).originalServiceIdOrReject().value).isEqualTo(id)
    }

    @Test
    fun `identyfikator zastępczy z frontendu jest odmową walidacji, która nazywa pole i wartość`() {
        assertThatThrownBy { request("temp-1790326470364").originalServiceIdOrReject() }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("temp-1790326470364")
            .hasMessageContaining("originalServiceId")
            .hasMessageContaining("nie jest zapisana w cenniku")
    }
}
