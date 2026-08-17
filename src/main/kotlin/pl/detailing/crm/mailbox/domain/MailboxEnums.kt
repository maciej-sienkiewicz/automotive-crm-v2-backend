package pl.detailing.crm.mailbox.domain

/**
 * How the studio's mailbox is reached. OAuth providers are detected up front so the
 * onboarding UI can route to the consent screen instead of asking for a password.
 */
enum class MailProviderType {
    GOOGLE_API,
    MS_GRAPH,
    IMAP_SMTP
}

enum class MailAuthType {
    OAUTH2,
    PASSWORD,
    APP_PASSWORD
}

enum class MailAccountStatus {
    ACTIVE,
    AUTH_FAILED,
    DISABLED
}

enum class MailDirection {
    INBOUND,
    OUTBOUND
}

enum class MailSendStatus {
    RECEIVED,
    OUTBOX_PENDING,
    SENT,
    FAILED
}

/**
 * Classification lives on the thread, not the message: once a conversation is
 * classified, every follow-up message inherits the verdict and costs no LLM call.
 */
enum class MailThreadClassification {
    PENDING,
    INQUIRY,
    OTHER,
    UNSURE
}
