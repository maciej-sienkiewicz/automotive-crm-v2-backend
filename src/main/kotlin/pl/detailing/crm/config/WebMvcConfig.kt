package pl.detailing.crm.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val subscriptionInterceptor: SubscriptionInterceptor
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(subscriptionInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/**",
                "/api/v1/auth/**",
                "/api/health",
                "/api/v1/vehicle-metadata/**",
                "/api/v1/inbound/email",
                "/api/mobile/**",
                // CardDAV uses HTTP Basic auth (stateless), not session-based auth.
                // SecurityContextHelper.getCurrentStudioId() is incompatible with CardDavUserDetails.
                "/api/v1/carddav/**",
                // Entitlements and pricing are always accessible (needed for expired/trial studios)
                "/api/v1/me/entitlements",
                "/api/v1/subscription/my-plan",
                "/api/v1/subscription/feature-plans",
                "/api/v1/subscription/add-ons",
                "/api/v1/subscription/calculate-price",
                "/api/v1/subscription/preview-plan-change",
                "/api/v1/subscription/preview-add-on",
                "/api/v1/subscription/payment-history",
                "/api/v1/subscription/start-trial",
                "/api/v1/subscription/status"
            )
    }
}