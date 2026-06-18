package pl.detailing.crm.shared

const val PII_MASK = "***"

/** Returns [PII_MASK] when [mask] is true and value is non-null, otherwise the original value. */
fun String?.maskIf(mask: Boolean): String? = if (mask && this != null) PII_MASK else this

/** Returns [PII_MASK] when [mask] is true, otherwise the original value. */
fun String.maskNonNullIf(mask: Boolean): String = if (mask) PII_MASK else this
