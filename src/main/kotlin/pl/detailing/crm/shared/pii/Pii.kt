package pl.detailing.crm.shared.pii

/**
 * Marks a `String` field of a response/payload class as **customer personal data**.
 *
 * Fields annotated with `@Pii` are serialized through [PiiMaskingModule]: the real value
 * is written only when the current [PiiAccessContext] grants access (i.e. the requesting
 * user holds `CUSTOMERS_VIEW`); otherwise the value is irreversibly replaced
 * with [pl.detailing.crm.shared.PII_MASK] **before it leaves the JVM**. There is no code
 * path that serializes an annotated field unmasked without an explicit, auditable
 * [PiiAccessContext.withGranted] block.
 *
 * Only `String`/`String?` fields may carry the annotation — [PiiMaskingModule] fails fast
 * at first serialization of a class that violates this, so a mistake cannot ship silently.
 *
 * Enforcement that the annotation is not *forgotten* on new DTOs lives in
 * `PiiResponseSurfaceScanTest`, which walks the response graphs of every `@RestController`
 * and fails the build when a personal-data-named `String` field is not annotated.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Pii
