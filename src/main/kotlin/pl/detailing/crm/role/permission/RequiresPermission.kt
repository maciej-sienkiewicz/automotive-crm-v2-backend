package pl.detailing.crm.role.permission

import pl.detailing.crm.role.domain.Permission

/**
 * Declares that the annotated controller method requires the authenticated user
 * to have the specified [Permission] in their custom role.
 *
 * Enforcement is performed by [PermissionAuthorizationAspect]. The check combines:
 * 1. Role assignment — the user must have a custom role with this permission.
 * 2. Feature gating — the studio must have the corresponding [pl.detailing.crm.subscription.entitlement.FeatureKey]
 *    enabled (when the permission's module requires one).
 *
 * Studio owners always bypass this check.
 *
 * Usage:
 * ```kotlin
 * @GetMapping("/invoices")
 * @RequiresPermission(Permission.FINANCE_VIEW_INVOICES)
 * fun listInvoices(): ResponseEntity<List<InvoiceDto>> { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresPermission(val value: Permission)
