package pl.detailing.crm.email.provider.javamail

import jakarta.mail.internet.InternetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regresja z produkcji: nadawca zaszyty w kodzie (kontakt@…) nie zgadzał się z kontem
 * SMTP (no-reply@detailboost.pl). OVH przyjmował wiadomość, log mówił „dispatched",
 * a filtr nadawcy odbijał ją po chwili: zaproszenia pracowników i resety hasła nigdy
 * nie docierały. Nadawca ma być kontem SMTP, chyba że konfiguracja mówi inaczej.
 */
class JavaMailProviderTest {

    private fun message(properties: JavaMailProperties) = JavaMailProvider(properties).let { provider ->
        provider.buildMessage(provider.buildSession(), "jan@example.pl", "Temat", "Treść", emptyList())
    }

    @Test
    fun `domyslnym nadawca jest uwierzytelnione konto SMTP`() {
        val msg = message(JavaMailProperties(username = "no-reply@detailboost.pl"))

        val from = msg.from.single() as InternetAddress
        assertEquals("no-reply@detailboost.pl", from.address)
        assertEquals("DetailBoost", from.personal)
        assertNull(msg.replyTo.takeIf { it.size > 1 })
        assertEquals("jan@example.pl", (msg.allRecipients.single() as InternetAddress).address)
    }

    @Test
    fun `nadawca, nazwa i Reply-To z konfiguracji`() {
        val msg = message(
            JavaMailProperties(
                username = "no-reply@detailboost.pl",
                from = "powiadomienia@detailboost.pl",
                fromName = "DetailBoost CRM",
                replyTo = "kontakt@detailboost.pl"
            )
        )

        val from = msg.from.single() as InternetAddress
        assertEquals("powiadomienia@detailboost.pl", from.address)
        assertEquals("DetailBoost CRM", from.personal)
        assertEquals("kontakt@detailboost.pl", (msg.replyTo.single() as InternetAddress).address)
    }
}
