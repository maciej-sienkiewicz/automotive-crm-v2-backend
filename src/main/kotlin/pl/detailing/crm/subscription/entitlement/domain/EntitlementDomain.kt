package pl.detailing.crm.subscription.entitlement.domain

import pl.detailing.crm.subscription.entitlement.FeatureKey
import java.util.UUID

data class Feature(
    val id: UUID,
    val key: FeatureKey,
    val isActive: Boolean
)

data class Plan(
    val id: UUID,
    val key: PlanKey,
    val name: String,
    val monthlyPriceGrossCents: Long,
    val features: Set<FeatureKey>,
    val isActive: Boolean,
    val displayOrder: Int
)

data class AddOn(
    val id: UUID,
    val key: AddOnKey,
    val name: String,
    val description: String?,
    val monthlyPriceGrossCents: Long?,
    val features: Set<FeatureKey>,
    val isActive: Boolean,
    val isAvailable: Boolean
)

/**
 * Resolved entitlement snapshot for a studio — computed once and cached in Redis.
 *
 * [planKey] is the active plan (BASIC or EVERYTHING).
 * [enabledFeatures] is the union of features from the plan and all active add-ons.
 * [activeAddOnKeys] lists every add-on currently active for the studio.
 */
data class StudioEntitlements(
    val planKey: PlanKey,
    val planName: String,
    val enabledFeatures: Set<FeatureKey>,
    val activeAddOnKeys: Set<AddOnKey>
) {
    fun hasFeature(key: FeatureKey): Boolean = key in enabledFeatures
}

enum class PlanKey(val displayName: String) {
    BASIC("Podstawowy"),
    EVERYTHING("Wszystko")
}

enum class AddOnKey(val displayName: String) {
    FINANCE_MODULE("Moduł Finansów"),
    EMPLOYEES_MODULE("Moduł Pracowników"),
    SMS_EMAIL_MODULE("SMS i E-maile")
}
