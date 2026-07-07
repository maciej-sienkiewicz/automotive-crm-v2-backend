package pl.detailing.crm.role.permission

import pl.detailing.crm.role.domain.Permission

/**
 * Declares that the annotated controller method or class requires the authenticated user
 * to have the specified [Permission] in their custom role.
 *
 * When placed on a class, all methods in that class require the permission.
 * A method-level annotation takes precedence over the class-level annotation.
 *
 * Enforcement is performed by [PermissionAuthorizationAspect]. The check combines:
 * 1. Role assignment — the user must have a custom role with this permission.
 * 2. Feature gating — the studio must have the corresponding [pl.detailing.crm.subscription.entitlement.FeatureKey]
 *    enabled (when the permission's module requires one).
 *
 * Studio owners always bypass this check.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresPermission(val value: Permission)
