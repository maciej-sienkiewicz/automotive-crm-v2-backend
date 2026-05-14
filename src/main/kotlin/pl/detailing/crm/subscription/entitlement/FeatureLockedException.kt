package pl.detailing.crm.subscription.entitlement

import pl.detailing.crm.shared.BusinessException

/**
 * Thrown when a studio attempts to access a feature not included in its active plan
 * or activated add-ons.
 *
 * This is NOT a security error — it is a paywall signal. [GlobalExceptionHandler]
 * maps it to HTTP 402 with a [FeatureLockedResponse] body so the frontend can
 * render a demo/mockup view with an upsell prompt instead of a generic error screen.
 */
class FeatureLockedException(
    val featureKey: FeatureKey,
    message: String = "Moduł '${featureKey.displayName}' nie jest dostępny w Twoim planie."
) : BusinessException(message)
