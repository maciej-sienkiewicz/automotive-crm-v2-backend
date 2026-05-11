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

    // ── HttpServletRequest attribute keys shared between aspect and filter ───
    /** Set by ApiMetricsAspect; read by HttpMetricsFilter for response-size tagging. */
    const val ATTR_CONTROLLER = "crm.obs.controller"
    const val ATTR_ENDPOINT = "crm.obs.endpoint"
    const val ATTR_USER_ROLE = "crm.obs.userRole"
}
