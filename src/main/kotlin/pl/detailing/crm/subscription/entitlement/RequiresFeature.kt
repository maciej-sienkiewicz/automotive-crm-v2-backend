package pl.detailing.crm.subscription.entitlement

/**
 * Declares that the annotated controller method requires the caller's studio
 * to have the specified [FeatureKey] enabled in its active entitlements.
 *
 * Enforcement is performed by [FeatureAuthorizationAspect] before the method executes.
 * When the feature is not enabled, [FeatureLockedException] is thrown, which the
 * [GlobalExceptionHandler] maps to HTTP 402 with a structured paywall payload.
 *
 * Usage:
 * ```kotlin
 * @GetMapping("/invoices")
 * @RequiresFeature(FeatureKey.FINANCE)
 * fun listInvoices(): ResponseEntity<List<InvoiceDto>> { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Deprecated(
    "Feature keys are an implementation detail of the price list. Declare the business " +
    "action instead via @RequiresCapability — capabilities support cross-module rules " +
    "and produce the richer MODULE_REQUIRED 402 payload.",
    ReplaceWith("RequiresCapability", "pl.detailing.crm.subscription.entitlement.capability.RequiresCapability")
)
annotation class RequiresFeature(val value: FeatureKey)
