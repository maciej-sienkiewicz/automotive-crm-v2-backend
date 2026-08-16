package pl.detailing.crm.subscription.entitlement.capability

/**
 * Declares that the annotated controller method (or every method of the annotated
 * class) requires the caller's STUDIO to have the given [CapabilityKey].
 *
 * Enforcement is performed by [CapabilityAuthorizationAspect] BEFORE any RBAC
 * check. This ordering is deliberate: capability checks are entitlement checks
 * (what the studio bought) and apply to every user including the studio owner,
 * while RBAC (@RequiresPermission / @RequiresOwner) is skipped for owners.
 * A paid module must never be reachable just because the caller is the owner.
 *
 * On denial the aspect throws [pl.detailing.crm.shared.CapabilityLockedException],
 * mapped by the GlobalExceptionHandler to HTTP 402 with code MODULE_REQUIRED and
 * a checkout-ready upsell payload.
 *
 * A method-level annotation overrides a class-level one.
 *
 * Usage:
 * ```kotlin
 * @PostMapping("/campaigns")
 * @RequiresCapability(CapabilityKey.COMM_SEND_CAMPAIGN)
 * fun createCampaign(...) { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresCapability(val value: CapabilityKey)
