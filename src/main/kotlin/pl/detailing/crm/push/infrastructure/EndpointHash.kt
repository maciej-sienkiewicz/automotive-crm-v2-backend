package pl.detailing.crm.push.infrastructure

import java.security.MessageDigest

/**
 * Lookup key of a subscription: `push_devices.endpoint_hash`. The endpoint itself
 * is too long for a unique index, so every lookup by endpoint goes through this.
 */
fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
