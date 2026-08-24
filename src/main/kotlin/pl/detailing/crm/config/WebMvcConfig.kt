package pl.detailing.crm.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val subscriptionInterceptor: SubscriptionInterceptor
) : WebMvcConfigurer {

    /**
     * SINGLE SOURCE OF TRUTH for billing-gate exclusions. SubscriptionInterceptor
     * intentionally holds no path list — add new exclusions only here, with a
     * comment explaining why the path must work for a not-yet-paying studio.
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(subscriptionInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                // Authentication itself must work regardless of billing state
                "/api/auth/**",
                "/api/v1/auth/**",
                "/api/health",
                "/api/v1/vehicle-metadata/**",
                // Manifest PWA nie jest funkcją produktu, tylko metadanymi instalacji —
                // studio z wygasłą subskrypcją i tak musi móc otworzyć aplikację,
                // choćby po to, żeby zapłacić.
                "/api/v1/pwa/manifest",
                "/api/mobile/**",
                // Signing tablet uses X-Tablet-Token (Redis), not session auth
                "/api/tablet/**",
                // CardDAV uses HTTP Basic auth (stateless), not session-based auth.
                // SecurityContextHelper.getCurrentStudioId() is incompatible with CardDavUserDetails.
                "/api/v1/carddav/**",
                // Entitlements read must work for expired/trial studios (renders the paywall)
                "/api/v1/me/entitlements",
                // The entire subscription-management surface must be reachable when the
                // subscription is inactive — that is exactly when the studio needs to
                // browse plans, start a trial, pay or renew.
                "/api/subscription/**",
                "/api/v1/subscription/**",
                // Przelewy24 server-to-server notifications (no session at all)
                "/api/v1/payments/**",
                // Platform metrics console — cross-tenant, has no studio to bill-check
                "/api/internal/**",
                // Telemetry must keep working while a studio is locked out of the product.
                // Blocking it would blind us precisely when a customer is hitting a paywall
                // and most likely to be having the experience worth measuring.
                "/api/v1/metrics/**"
            )
    }
}