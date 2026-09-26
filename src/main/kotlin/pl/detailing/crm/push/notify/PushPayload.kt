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
    NEW_LEAD,
    RESERVATION_CREATED,
    VEHICLE_CHECKED_IN,

    /** A company started advertising in the area the studio tracks (competition monitoring). */
    AREA_CAMPAIGN,

    /** Domknął się okres, za który użytkownik chce raport (Statystyki → Raport PDF). */
    OWNER_REPORT_READY,

    /** Sent on request from the pairing wizard — proof that the whole chain works. */
    TEST
}

enum class PushIcon {
    EARNINGS,
    LEAD,
    RESERVATION,
    CHECKIN,
    CAMPAIGN,

    /** The app's own mark — for notifications about the app itself, not a business event. */
    APP
}
