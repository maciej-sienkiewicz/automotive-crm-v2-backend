package pl.detailing.crm.role.permission

import pl.detailing.crm.role.domain.Permission

/**
 * Declares that the annotated controller method or class requires the authenticated user
 * to have **at least one** of the specified [Permission]s (ANY-OF semantics) in their
 * custom role. The single-permission form `@RequiresPermission(Permission.X)` is the
 * common case; multiple values express endpoints shared between capability areas
 * (e.g. the dashboard revenue tile: finance reports OR statistics).
 *
 * When placed on a class, all methods in that class require the permission(s).
 * A method-level annotation takes precedence over the class-level annotation.
 *
 * Enforcement is performed by [PermissionAuthorizationAspect]. The check combines:
 * 1. Role assignment — the user must have a custom role with one of these permissions.
 * 2. Feature gating — the studio must have the corresponding [pl.detailing.crm.subscription.entitlement.FeatureKey]
 *    enabled (when the permission's module requires one).
 *
 * Studio owners always bypass this check.
 *
 * An empty value list is a programming error and is treated as fail-closed (always deny).
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresPermission(vararg val value: Permission)
