package pl.detailing.crm.audit.feed

import pl.detailing.crm.audit.domain.AuditValueType
import java.time.Instant

/**
 * A page of the activity feed.
 *
 * Paged by cursor rather than page number: the feed is sorted newest-first over a table
 * that grows continuously, so an offset would both drift as new events arrive and get
 * slower the further back the reader scrolls.
 */
data class AuditFeedResponse(
    val items: List<AuditFeedItem>,
    /** Pass back as `cursor` to fetch the next page. Null when the end has been reached. */
    val nextCursor: String?,
    val hasMore: Boolean
)

/**
 * One entry, ready to render.
 *
 * The contract is deliberately "display strings plus the raw values behind them": a client
 * can bind [title], [description] and [AuditChangeView.newValueDisplay] straight into the
 * markup, while [subject], [amount] and the raw values stay available for clients that
 * want to build their own layout, link, or chart.
 */
data class AuditFeedItem(
    val id: String,
    val occurredAt: Instant,

    val actor: AuditActorView,
    val module: AuditCodeLabel,
    val action: AuditCodeLabel,
    val severity: AuditCodeLabel,
    val channel: AuditCodeLabel,

    /** Semantic icon key — see `AuditIcon`. The frontend owns the icon set. */
    val icon: String,

    /** "Przyjęto pojazd — Audi A4 (WX 1234)" */
    val title: String,
    /** Context line: "Klient: Jan Kowalski · Wizyta #2026/02/17". Null when there is none. */
    val description: String?,
    /** Single-line form for compact views and notifications. */
    val summary: String,

    /** What the action was performed on. Null for modules with no addressable resource. */
    val subject: AuditReferenceView?,
    /** Other business objects this event touches — the drill-down targets. */
    val related: List<AuditReferenceView>,

    val amount: AuditAmountView?,

    val changes: List<AuditChangeView>,
    /** "Status, Planowane zakończenie" — for collapsed rows. Null when nothing changed. */
    val changeSummary: String?,

    val metadata: Map<String, String>,
    /** Shared by all entries produced by one user gesture, so a client can group them. */
    val correlationId: String?,
    val ipAddress: String?
)

/**
 * A code plus its Polish label. Every enum the API exposes is shaped this way so a client
 * can render a filter chip without shipping its own translation table, while still
 * switching on the stable [code].
 */
data class AuditCodeLabel(
    val code: String,
    val label: String
)

data class AuditActorView(
    /** EMPLOYEE | CUSTOMER | SYSTEM | INTEGRATION */
    val type: String,
    val typeLabel: String,
    /** User id for employees, customer id for customers, null for automated actors. */
    val id: String?,
    val displayName: String,
    /** "JK" — precomputed so avatars look the same everywhere. */
    val initials: String
)

/**
 * A pointer to a business object. The backend emits a resource type and an id, never a
 * URL: routing is the frontend's concern and hardcoding paths here would couple the API
 * to one client's router.
 */
data class AuditReferenceView(
    /** CUSTOMER | VEHICLE | VISIT | APPOINTMENT | ... */
    val resource: String,
    val id: String,
    val label: String?
)

data class AuditAmountView(
    /** Plain decimal, for clients that want to compute with it. */
    val value: String,
    val currency: String,
    /** "1 230,00 zł" */
    val display: String
)

data class AuditChangeView(
    val field: String,
    val label: String,
    val type: AuditValueType,
    val oldValue: String?,
    val newValue: String?,
    val oldValueDisplay: String?,
    val newValueDisplay: String?
)

/**
 * Everything the UI needs to build its filter bar, in one call. Returned by
 * `GET /api/v1/audit/filters`.
 */
data class AuditFilterOptionsResponse(
    val modules: List<AuditFilterOption>,
    val actions: List<AuditActionFilterOption>,
    val actorTypes: List<AuditFilterOption>,
    val severities: List<AuditFilterOption>,
    val channels: List<AuditFilterOption>
)

data class AuditFilterOption(
    val value: String,
    val label: String,
    val icon: String? = null
)

/**
 * An action option carries its icon and default severity, so the filter list can be
 * grouped and colour-coded the same way the feed rows are without a second lookup.
 * Actions are intentionally not scoped to a module — the same `UPDATE` or `COMMENT_ADDED`
 * is emitted from several of them.
 */
data class AuditActionFilterOption(
    val value: String,
    val label: String,
    val icon: String,
    val severity: String
)
