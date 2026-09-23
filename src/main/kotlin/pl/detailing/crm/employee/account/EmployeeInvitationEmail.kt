package pl.detailing.crm.employee.account

import pl.detailing.crm.auth.passwordreset.PasswordResetProperties

/**
 * Zaproszenie pracownika do DetailBoost - wspólne dla założenia konta i dla
 * „Wyślij maila ponownie", żeby oba e-maile mówiły to samo.
 */
internal object EmployeeInvitationEmail {

    const val SUBJECT = "Zaproszenie do DetailBoost – skonfiguruj swoje konto"

    /** Link do ekranu, na którym pracownik ustawia hasło (ten sam co przy resecie hasła). */
    fun setupLink(properties: PasswordResetProperties, rawToken: String): String =
        "${properties.frontendBaseUrl.trimEnd('/')}/confirm-password?token=$rawToken"

    fun body(firstName: String, invitedByName: String?, setupLink: String, validFor: String): String {
        val inviter = invitedByName?.let { "Użytkownik $it" } ?: "Administrator"
        return """
            Cześć $firstName,

            $inviter zaprosił(-a) Cię do platformy DetailBoost.

            Aby aktywować swoje konto i ustawić hasło, kliknij w poniższy link:
            $setupLink

            Link jest aktywny przez $validFor. Po tym czasie wygaśnie i będziesz musiał(-a) poprosić administratora o ponowne wysłanie zaproszenia.

            Jeśli nie spodziewałeś(-aś) się tego zaproszenia, możesz zignorować tę wiadomość.

            Pozdrawiamy,
            Zespół DetailBoost
        """.trimIndent()
    }

    /** „48 godzin", „24 godziny", „1 godzinę" — odmiana do zdania „aktywny przez …". */
    fun hoursInPolish(hours: Long): String {
        val unit = when {
            hours == 1L -> "godzinę"
            hours % 10 in 2..4 && hours % 100 !in 12..14 -> "godziny"
            else -> "godzin"
        }
        return "$hours $unit"
    }
}
