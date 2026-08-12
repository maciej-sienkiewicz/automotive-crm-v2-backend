package pl.detailing.crm.role.permission

/**
 * Declares that the annotated controller method or class is accessible **only to the
 * studio owner** (`users.is_owner`). No custom role can grant this access — it covers
 * operations that are structurally the owner's: billing, subscription purchases,
 * company-wide configuration, KSeF credentials.
 *
 * When placed on a class, all methods in that class are owner-only. A method-level
 * [RequiresPermission] does NOT override a class-level [RequiresOwner] — owner-only
 * is the stronger constraint and always wins when both are present on the resolved
 * target.
 *
 * Enforcement is performed by [PermissionAuthorizationAspect].
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresOwner
