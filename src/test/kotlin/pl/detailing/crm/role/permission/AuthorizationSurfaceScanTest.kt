package pl.detailing.crm.role.permission

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Method
import org.junit.jupiter.api.Assertions.fail

/**
 * Fail-closed guarantee for the whole REST surface.
 *
 * Every handler method of every `@RestController` must resolve to an authorization
 * declaration: `@RequiresPermission` or `@RequiresOwner` (method- or class-level) — or be
 * explicitly listed here with a justification. A new controller added without a gate and
 * without a conscious allowlist entry fails the build.
 *
 * This mirrors the approach of PiiResponseSurfaceScanTest: the security property is
 * enforced by construction, not by review discipline.
 */
class AuthorizationSurfaceScanTest {

    /**
     * Endpoints that are deliberately reachable without a permission gate.
     * Key: controller simple name; value ALL_METHODS or a set of method names.
     *
     * Categories:
     * - PUBLIC       — anonymous by design, listed in SecurityConfig permitAll
     * - TOKEN        — authenticated by an opaque token header/URL token, not a session
     * - WEBHOOK      — called by external providers, signature/secret-verified
     * - SELF_SERVICE — operates exclusively on the authenticated user's own data
     * - ALL_USERS    — intentionally available to every authenticated studio member
     */
    private val allowlist: Map<String, Set<String>> = mapOf(
        // PUBLIC — anonymous auth flows (SecurityConfig permitAll)
        "AuthController" to ALL_METHODS,
        "HealthController" to ALL_METHODS,
        "DemoAccountController" to ALL_METHODS,
        "VehicleMetadataController" to ALL_METHODS,       // public dictionary data (makes/models)
        "PublicSignatureController" to ALL_METHODS,       // opaque URL token
        "PublicUserSignatureController" to ALL_METHODS,   // opaque URL token
        "PublicVisitCardController" to ALL_METHODS,       // opaque URL token
        // TOKEN — Redis-token authenticated device flows
        "TabletSignatureController" to ALL_METHODS,       // X-Tablet-Token
        "MobileUploadController" to ALL_METHODS,          // X-Upload-Token
        "MobileVoiceController" to ALL_METHODS,           // mobile voice token
        "CardDavController" to ALL_METHODS,               // HTTP Basic (CardDavSecurityConfig)
        "WellKnownCardDavController" to ALL_METHODS,
        // WEBHOOK — provider callbacks
        "Przelewy24WebhookController" to ALL_METHODS,
        "SmsInboundController" to ALL_METHODS,
        "InboundController" to ALL_METHODS,
        "InboundEmailWebhookController" to ALL_METHODS,
        // SELF_SERVICE — the authenticated user's own data only
        "ProfileController" to ALL_METHODS,
        "MyWorkTimeController" to ALL_METHODS,
        "PinController" to ALL_METHODS,                   // PIN user switching; unlock is owner-checked inline
        "MyTasksController" to ALL_METHODS,               // only tasks visible to the caller (TaskVisibility)
        // ALL_USERS — intentionally available to every authenticated studio member
        "EmployeeController" to setOf("listEmployees"),   // coworker names for calendar assignment
        "EmployeeLeaveController" to setOf("leaveCalendar"), // per-day on-leave counts for the shared calendar
        "CompanyController" to setOf(                     // read-only studio branding/config
            "getCompanySettings", "getEmailAlias", "getLeadAlertConfig", "getIdleTimeout"
        ),
        "SubscriptionController" to ALL_METHODS,          // status for gates; mutations owner-checked inline
        "EntitlementsController" to ALL_METHODS,          // entitlements drive the UI gates; mutations owner-checked inline
    )

    @Test
    fun `every REST handler method declares its authorization or is consciously allowlisted`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))

        val violations = mutableListOf<String>()
        val staleAllowlist = allowlist.keys.toMutableSet()

        scanner.findCandidateComponents("pl.detailing.crm")
            .mapNotNull(BeanDefinition::getBeanClassName)
            .map { Class.forName(it) }
            .sortedBy { it.simpleName }
            .forEach { controller ->
                val allowedMethods = allowlist[controller.simpleName]
                if (allowedMethods != null) staleAllowlist.remove(controller.simpleName)

                val classGated = controller.isAnnotationPresent(RequiresPermission::class.java) ||
                    controller.isAnnotationPresent(RequiresOwner::class.java)

                controller.declaredMethods
                    .filter { it.isHandlerMethod() }
                    .forEach { method ->
                        val gated = classGated ||
                            method.isAnnotationPresent(RequiresPermission::class.java) ||
                            method.isAnnotationPresent(RequiresOwner::class.java)
                        val allowlisted = allowedMethods === ALL_METHODS ||
                            allowedMethods?.contains(method.name) == true

                        if (!gated && !allowlisted) {
                            violations += "${controller.simpleName}.${method.name}"
                        }
                    }
            }

        if (violations.isNotEmpty()) {
            fail<Unit>(
                "Ungated REST handler methods (add @RequiresPermission/@RequiresOwner " +
                    "or a justified allowlist entry):\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }

        if (staleAllowlist.isNotEmpty()) {
            fail<Unit>("Stale allowlist entries (controller no longer exists): $staleAllowlist")
        }
    }

    private fun Method.isHandlerMethod(): Boolean =
        AnnotatedElementUtils.hasAnnotation(this, RequestMapping::class.java)

    companion object {
        private val ALL_METHODS: Set<String> = setOf("*ALL*")
    }
}
