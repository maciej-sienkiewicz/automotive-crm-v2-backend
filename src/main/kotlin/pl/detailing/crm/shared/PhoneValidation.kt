package pl.detailing.crm.shared

/**
 * Validates Polish phone numbers
 * Accepts formats: +48123456789, +48 123 456 789, 123456789, etc.
 */
fun isValidPolishPhone(phone: String): Boolean {
    // Remove all whitespace and dashes
    val cleaned = phone.replace(Regex("[\\s-]"), "")

    // Check for +48 prefix
    if (cleaned.startsWith("+48")) {
        val number = cleaned.substring(3)
        return number.matches(Regex("^\\d{9}$"))
    }

    // Check for direct 9-digit format
    if (cleaned.matches(Regex("^\\d{9}$"))) {
        return true
    }

    return false
}

/**
 * Normalizes Polish phone number to +48XXXXXXXXX format
 */
fun normalizePolishPhone(phone: String): String {
    val cleaned = phone.replace(Regex("[\\s-]"), "")

    return if (cleaned.startsWith("+48")) {
        cleaned
    } else if (cleaned.matches(Regex("^\\d{9}$"))) {
        "+48$cleaned"
    } else {
        phone // Return as-is if invalid
    }
}
