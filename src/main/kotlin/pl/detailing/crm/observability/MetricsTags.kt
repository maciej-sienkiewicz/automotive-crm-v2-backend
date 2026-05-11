package pl.detailing.crm.observability

/**
 * Central registry of metric names, tag keys and well-known tag values used across
 * the entire observability layer.  Changing a string here propagates everywhere –
 * no silent drift between aspect, filter and Prometheus dashboards.
 */
object MetricsTags {

    // ── Metric names (Micrometer dots → Prometheus underscores) ─────────────
    /** Timer: request count + latency histogram.  Prometheus: crm_api_request_duration_seconds */
    const val API_REQUEST_DURATION = "crm.api.request.duration"

    /** Counter: only errors (4xx / 5xx), with fine-grained exception tag. */
    const val API_ERRORS_TOTAL = "crm.api.errors.total"

    /** DistributionSummary: response body bytes – used for security anomaly detection. */
    const val API_RESPONSE_SIZE = "crm.api.response.size"

    /** Counter: HTTP-level rejections by Spring Security (never reach a controller). */
    const val API_SECURITY_REJECTIONS = "crm.api.security.rejections"

    // ── Tag keys ──────────────────────────────────────────────────────────────
    /** URI path template, e.g. /api/v1/customers/{customerId} */
    const val TAG_PATH = "path"

    /** HTTP verb: GET, POST, PUT, DELETE, PATCH */
    const val TAG_METHOD = "method"

    /** HTTP status code as string: "200", "403", "500" */
    const val TAG_STATUS = "status"

    /** Simple class name of the thrown exception, or TAG_VALUE_NONE if no exception. */
    const val TAG_EXCEPTION = "exception"

    /** Spring @RestController simple class name, e.g. "CustomerController" */
    const val TAG_CONTROLLER = "controller"

    /** Controller method (handler) name, e.g. "getCustomer" */
    const val TAG_ENDPOINT = "endpoint"

    /** First Spring Security GrantedAuthority, e.g. "ROLE_ADMIN" */
    const val TAG_USER_ROLE = "user_role"

    // ── Canonical tag values ──────────────────────────────────────────────────
    const val TAG_VALUE_UNKNOWN = "unknown"
    const val TAG_VALUE_ANONYMOUS = "anonymous"
    const val TAG_VALUE_NONE = "none"

    // ── Communication metrics ─────────────────────────────────────────────────
    /** Counter: emails dispatched via OutboundCommunicationGateway. Prometheus: crm_communication_emails_sent_total */
    const val COMM_EMAIL_SENT = "crm.communication.emails.sent"

    /** Counter: SMS messages dispatched via OutboundCommunicationGateway. Prometheus: crm_communication_sms_sent_total */
    const val COMM_SMS_SENT = "crm.communication.sms.sent"

    // ── Visit lifecycle metrics ───────────────────────────────────────────────
    /** Counter: visits transitioned to IN_PROGRESS (work started). */
    const val VISITS_STARTED = "crm.visits.started"

    /** Counter: visits transitioned to COMPLETED (vehicle picked up by customer). */
    const val VISITS_COMPLETED = "crm.visits.completed"

    // ── Storage metrics ───────────────────────────────────────────────────────
    /** Gauge: total disk bytes on the server root filesystem. */
    const val STORAGE_DISK_TOTAL = "crm.storage.disk.total.bytes"

    /** Gauge: free (usable) disk bytes on the server root filesystem. */
    const val STORAGE_DISK_FREE = "crm.storage.disk.free.bytes"

    /** Gauge: S3 bucket size in bytes (refreshed from CloudWatch, 24h lag). */
    const val STORAGE_S3_SIZE = "crm.storage.s3.size.bytes"

    /** Gauge: number of objects in the S3 bucket (refreshed from CloudWatch, 24h lag). */
    const val STORAGE_S3_OBJECTS = "crm.storage.s3.objects.total"

    // ── Additional tag keys ───────────────────────────────────────────────────
    /** UUID of the studio (tenant) for multi-tenant breakdowns. */
    const val TAG_STUDIO_ID = "studio_id"

    /** Outcome of a communication attempt: sent / failed / no_credits. */
    const val TAG_RESULT = "result"

    /** S3 bucket name. */
    const val TAG_BUCKET = "bucket"

    // ── HttpServletRequest attribute keys shared between aspect and filter ───
    /** Set by ApiMetricsAspect; read by HttpMetricsFilter for response-size tagging. */
    const val ATTR_CONTROLLER = "crm.obs.controller"
    const val ATTR_ENDPOINT = "crm.obs.endpoint"
    const val ATTR_USER_ROLE = "crm.obs.userRole"
}
