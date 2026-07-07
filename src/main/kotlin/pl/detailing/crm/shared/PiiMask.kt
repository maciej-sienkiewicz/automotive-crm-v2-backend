package pl.detailing.crm.shared

/**
 * Irreversible replacement value for masked personal data.
 *
 * Masking is enforced centrally at the serialization boundary — see
 * [pl.detailing.crm.shared.pii.Pii] and [pl.detailing.crm.shared.pii.PiiMaskingModule].
 * Do not hand-mask fields in controllers.
 */
const val PII_MASK = "***"
