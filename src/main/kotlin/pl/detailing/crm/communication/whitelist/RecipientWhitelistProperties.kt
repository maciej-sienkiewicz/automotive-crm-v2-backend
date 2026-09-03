package pl.detailing.crm.communication.whitelist

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Global list of phone numbers and e-mail addresses that customer messages may be sent to.
 *
 * Bound from `communication.whitelist.*` (env: COMMUNICATION_WHITELIST_ENABLED,
 * COMMUNICATION_WHITELIST_PHONES, COMMUNICATION_WHITELIST_EMAILS; lists are comma-separated).
 *
 * Semantics, decided with the founders:
 *  - [enabled] is true by default: the installation starts in the test phase, where only
 *    the listed recipients may receive customer messages;
 *  - an enabled whitelist with an empty list blocks that channel entirely — there is no
 *    "empty means everyone" shortcut, because that is exactly how a list gets forgotten;
 *  - switching [enabled] to false is the go-live decision: every customer may be reached;
 *  - the whitelist never applies to a message the studio redirected to its own phone or
 *    inbox (see [pl.detailing.crm.communication.OutboundCommunicationGateway]).
 */
@ConfigurationProperties(prefix = "communication.whitelist")
data class RecipientWhitelistProperties(
    val enabled: Boolean = true,
    /** Phone numbers in any Polish or E.164 spelling; normalized before comparison. */
    val phones: List<String> = emptyList(),
    /** E-mail addresses; compared case-insensitively. */
    val emails: List<String> = emptyList()
)
