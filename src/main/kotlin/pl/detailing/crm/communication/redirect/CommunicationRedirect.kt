package pl.detailing.crm.communication.redirect

import java.time.Instant

/** What the settings screen reads and writes. */
data class CommunicationRedirectSettings(
    val enabled: Boolean,
    val phone: String,
    val email: String,
    val updatedAt: Instant?
) {
    companion object {
        val OFF = CommunicationRedirectSettings(enabled = false, phone = "", email = "", updatedAt = null)
    }
}

/**
 * A redirect that is switched on right now. Only ever produced when both targets are set,
 * so callers never have to ask "enabled, but to whom?".
 */
data class ActiveRedirect(val phone: String, val email: String) {

    /**
     * Prefix stamped on every redirected message so the person reading their own phone
     * can tell a redirected customer message from a message meant for them, and can see
     * who would have received it. Short on purpose: it costs SMS characters.
     */
    fun prefixFor(originalRecipient: String): String = "[TEST → $originalRecipient] "
}
