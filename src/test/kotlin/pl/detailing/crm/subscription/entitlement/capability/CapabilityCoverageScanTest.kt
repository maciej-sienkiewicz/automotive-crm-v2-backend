package pl.detailing.crm.subscription.entitlement.capability

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.subscription.entitlement.FeatureKey
import java.lang.reflect.Method

/**
 * Fail-closed guarantee for MODULE gating, complementing AuthorizationSurfaceScanTest
 * (which guards RBAC). RBAC alone is not enough: studio owners bypass RBAC entirely,
 * so a paid-module controller relying only on @RequiresPermission is fully open to
 * the product's primary persona.
 *
 * Rule: every @RestController living in a paid-module package must carry a
 * @RequiresCapability (class-level, or on every handler method) whose capability
 * actually requires that module's feature — or be listed here with a justification.
 * A new controller added to a paid module without a capability gate fails the build.
 */
class CapabilityCoverageScanTest {

    /** Package prefix → the FeatureKey its controllers' capabilities must require. */
    private val paidModulePackages: Map<String, FeatureKey> = mapOf(
        "pl.detailing.crm.instagram" to FeatureKey.INSTAGRAM_MONITORING,
        "pl.detailing.crm.statistics" to FeatureKey.STATISTICS,
        "pl.detailing.crm.finance" to FeatureKey.FINANCE,
        "pl.detailing.crm.ksef" to FeatureKey.FINANCE,
        "pl.detailing.crm.campaigns" to FeatureKey.CAMPAIGNS,
        "pl.detailing.crm.smscampaigns" to FeatureKey.SMS_EMAIL,
        "pl.detailing.crm.email" to FeatureKey.SMS_EMAIL,
        "pl.detailing.crm.smscredits" to FeatureKey.SMS_EMAIL,
        "pl.detailing.crm.signing" to FeatureKey.E_SIGNATURES,
        "pl.detailing.crm.visit.smsreminder" to FeatureKey.SMS_EMAIL
    )

    /**
     * Controllers in paid-module packages deliberately reachable without a
     * capability gate. Every entry needs a reason:
     *
     * - TOKEN devices/links: their sessions/links can only be CREATED through a
     *   capability-gated path (RequestSignatureHandler enforces SIGNATURE_LOCAL /
     *   SIGNATURE_REMOTE_REQUEST at the point of effect), so the device-side flow
     *   completing an already-authorized session must keep working.
     * - WEBHOOK: called by external providers, not by studio users.
     */
    private val allowlist: Set<String> = setOf(
        "TabletSignatureController",     // TOKEN — X-Tablet-Token device flow
        "PublicSignatureController",     // TOKEN — customer signing link
        "SmsInboundController"           // WEBHOOK — provider delivery/opt-out callbacks
    )

    @Test
    fun `every paid-module controller declares a matching capability or is consciously allowlisted`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))

        val violations = mutableListOf<String>()
        val staleAllowlist = allowlist.toMutableSet()

        scanner.findCandidateComponents("pl.detailing.crm")
            .mapNotNull(BeanDefinition::getBeanClassName)
            .map { Class.forName(it) }
            .sortedBy { it.simpleName }
            .forEach { controller ->
                val expectedFeature = paidModulePackages.entries
                    .firstOrNull { (pkg, _) -> controller.packageName.startsWith(pkg) }
                    ?.value
                    ?: return@forEach

                if (controller.simpleName in allowlist) {
                    staleAllowlist.remove(controller.simpleName)
                    return@forEach
                }

                val classAnnotation = controller.getAnnotation(RequiresCapability::class.java)
                val handlerMethods = controller.declaredMethods.filter { it.isHandlerMethod() }

                handlerMethods.forEach { method ->
                    val effective = method.getAnnotation(RequiresCapability::class.java) ?: classAnnotation
                    val referencedFeatures = effective
                        ?.let { it.value.requiredFeatures + it.value.anyOfFeatures }
                        .orEmpty()
                    when {
                        effective == null ->
                            violations += "${controller.simpleName}.${method.name}: no @RequiresCapability " +
                                "(module package requires feature $expectedFeature)"
                        expectedFeature !in referencedFeatures ->
                            violations += "${controller.simpleName}.${method.name}: capability " +
                                "${effective.value} does not reference $expectedFeature — wrong module mapping"
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail<Unit>(
                "Paid-module endpoints without a correct capability gate " +
                    "(add @RequiresCapability or a justified allowlist entry):\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }

        if (staleAllowlist.isNotEmpty()) {
            fail<Unit>("Stale allowlist entries (controller no longer exists): $staleAllowlist")
        }
    }

    private fun Method.isHandlerMethod(): Boolean =
        AnnotatedElementUtils.hasAnnotation(this, RequestMapping::class.java)
}
