package pl.detailing.crm.observability

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Servlet filter that complements ApiMetricsAspect with two additional signals
 * that are only visible at the HTTP transport layer:
 *
 * 1. crm_api_response_size_bytes  (DistributionSummary)
 *    Tracks the number of bytes written to each response without buffering the
 *    body in memory (uses a counting OutputStream wrapper).  Combined with the
 *    path tag this supports security anomaly detection – e.g. an unusually large
 *    response to a normally small endpoint may indicate data exfiltration.
 *
 * 2. crm_api_security_rejections_total  (Counter)
 *    Counts requests that were rejected by the Spring Security filter chain
 *    before they ever reached a controller (HTTP 401 / 403).  These calls are
 *    invisible to the AOP aspect and are essential for detecting brute-force
 *    or credential-stuffing attacks.
 *
 * The filter is ordered at HIGHEST_PRECEDENCE + 10 to sit just after the
 * security filter chain so it can observe security-rejected responses too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class HttpMetricsFilter(private val registry: MeterRegistry) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val countingResponse = CountingResponseWrapper(response)

        try {
            chain.doFilter(request, countingResponse)
        } finally {
            val bytesWritten = countingResponse.bytesWritten
            val status = countingResponse.status
            val path = pathTemplate(request)
            val method = request.method

            // ── Response size metric ─────────────────────────────────────────
            DistributionSummary.builder(MetricsTags.API_RESPONSE_SIZE)
                .description("CRM API response body size in bytes")
                .baseUnit("bytes")
                .tag(MetricsTags.TAG_PATH, path)
                .tag(MetricsTags.TAG_METHOD, method)
                .tag(MetricsTags.TAG_STATUS, status.toString())
                .tag(MetricsTags.TAG_CONTROLLER, request.getAttribute(MetricsTags.ATTR_CONTROLLER)?.toString() ?: MetricsTags.TAG_VALUE_UNKNOWN)
                .tag(MetricsTags.TAG_USER_ROLE, request.getAttribute(MetricsTags.ATTR_USER_ROLE)?.toString() ?: MetricsTags.TAG_VALUE_ANONYMOUS)
                .register(registry)
                .record(bytesWritten.toDouble())

            // ── Security rejection counter (401 / 403 before controllers) ───
            // The AOP aspect never runs for these requests, so we record them here.
            if ((status == 401 || status == 403) && request.getAttribute(MetricsTags.ATTR_CONTROLLER) == null) {
                registry.counter(
                    MetricsTags.API_SECURITY_REJECTIONS,
                    MetricsTags.TAG_PATH, path,
                    MetricsTags.TAG_METHOD, method,
                    MetricsTags.TAG_STATUS, status.toString()
                ).increment()
            }
        }
    }

    private fun pathTemplate(request: HttpServletRequest): String =
        request.getAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern")
            ?.toString()
            ?: normalizePath(request.requestURI)

    /**
     * Strips numeric path segments so that /api/v1/customers/42/vehicles/7
     * becomes /api/v1/customers/{id}/vehicles/{id}.  Applied only when Spring
     * has not set bestMatchingPattern (e.g. for 404 / security-rejected paths).
     */
    private fun normalizePath(uri: String): String =
        uri.split("/")
            .joinToString("/") { segment ->
                if (segment.matches(Regex("\\d+"))) "{id}" else segment
            }

    // ── Counting response wrapper ────────────────────────────────────────────

    private class CountingResponseWrapper(response: HttpServletResponse) :
        HttpServletResponseWrapper(response) {

        private val counter = AtomicLong(0L)
        val bytesWritten: Long get() = counter.get()

        private inner class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
            override fun write(b: Int) {
                counter.incrementAndGet()
                delegate.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                counter.addAndGet(len.toLong())
                delegate.write(b, off, len)
            }

            override fun flush() = delegate.flush()
            override fun close() = delegate.close()
        }

        override fun getOutputStream(): ServletOutputStream {
            val delegate = super.getOutputStream()
            val counting = CountingOutputStream(delegate)
            return object : ServletOutputStream() {
                override fun isReady(): Boolean = delegate.isReady
                override fun setWriteListener(listener: WriteListener) = delegate.setWriteListener(listener)
                override fun write(b: Int) = counting.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = counting.write(b, off, len)
                override fun flush() = counting.flush()
                override fun close() = counting.close()
            }
        }
    }
}
