package pl.detailing.crm.push.notify

/**
 * Wire contract between the backend and the Service Worker's `push` handler
 * (frontend: public/service-worker.js). Field names are part of the API.
 *
 * The COPY lives here, not in the worker. A notification's wording is a product
 * decision that changes often; the worker is a cached artefact that reaches
 * phones slowly and unevenly. Keeping the text server-side means a reworded
 * notification ships with a backend deploy instead of waiting for every phone
 * to pick up a new worker.
 *
 * [icon] is a KEY, not a path: the worker owns the mapping to actual files, so
 * the backend never encodes URLs of frontend assets it cannot see.
 */
data class PushPayload(
    val type: PushNotificationType,
    val title: String,
    val body: String,
    /** In-app path opened when the notification is tapped. */
    val url: String,
    val icon: PushIcon,
    /** Collapse key: a newer notification of the same tag replaces the older one. */
    val tag: String
)

enum class PushNotificationType {
    VISIT_COMPLETED,
    NEW_LEAD
}

enum class PushIcon {
    EARNINGS,
    LEAD
}
