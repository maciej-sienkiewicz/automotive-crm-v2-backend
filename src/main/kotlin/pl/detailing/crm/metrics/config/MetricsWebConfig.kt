package pl.detailing.crm.metrics.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import pl.detailing.crm.metrics.apiaudit.ApiUsageTrackingInterceptor

/**
 * Registers the two metrics interceptors.
 *
 * Kept separate from [pl.detailing.crm.config.WebMvcConfig] on purpose: that class
 * documents itself as the single source of truth for *billing-gate* exclusions, and
 * mixing an unrelated concern into it is how such a rule quietly stops holding.
 * Spring composes every [WebMvcConfigurer] on the context, so both apply.
 */
@Configuration
class MetricsWebConfig(
    private val apiUsageTrackingInterceptor: ApiUsageTrackingInterceptor,
    private val platformAccessInterceptor: PlatformAccessInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // Usage tracking observes everything under /api and filters internally so that
        // the ignore-list stays configurable (crm.metrics.api-audit.ignored-path-prefixes).
        registry.addInterceptor(apiUsageTrackingInterceptor)
            .addPathPatterns("/api/**")
            .order(100)

        // Cross-tenant platform console: shared-secret gate, before anything else.
        registry.addInterceptor(platformAccessInterceptor)
            .addPathPatterns("/api/internal/**")
            .order(-100)
    }
}
