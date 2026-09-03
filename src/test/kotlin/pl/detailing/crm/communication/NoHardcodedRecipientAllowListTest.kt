package pl.detailing.crm.communication

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against the two test-phase hacks that once shipped in production code: an e-mail
 * allow-list baked into JavaMailProvider and an SMS whitelist in application.properties.
 * Both silently blocked every real customer. Who receives a message is decided per studio
 * by the communication redirect switch, in the gateway, and nowhere else.
 */
class NoHardcodedRecipientAllowListTest {

    private val forbidden = listOf("allowedMails", "smsapi.whitelist", "celowo zablokowany", "Faza testowa")

    private val files = listOf(
        "src/main/kotlin/pl/detailing/crm/email/provider/javamail/JavaMailProvider.kt",
        "src/main/kotlin/pl/detailing/crm/smscampaigns/provider/smsapi/SmsApiProvider.kt",
        "src/main/kotlin/pl/detailing/crm/smscampaigns/provider/smsapi/SmsApiProperties.kt",
        "src/main/resources/application.properties",
        "src/main/resources/application-docker-props.properties"
    )

    @Test
    fun `providers and config carry no recipient allow-list`() {
        files.map(::File).filter { it.exists() }.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertTrue(token !in text, "${file.path} zawiera „$token” — lista odbiorców nie może wrócić do providera ani do properties")
            }
        }
    }

    @Test
    fun `smtp password has no default value in the repository`() {
        val props = File("src/main/resources/application.properties").readText()
        assertTrue(Regex("""email\.javamail\.password=\$\{MAIL_PASSWORD:}""").containsMatchIn(props), "hasło SMTP musi pochodzić wyłącznie z env")
    }
}
