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
