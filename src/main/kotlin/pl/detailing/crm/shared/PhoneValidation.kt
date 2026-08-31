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

/**
 * Sprowadza numer do E.164 (`+48534920205`) albo zwraca `null`, jeśli się nie da.
 *
 * Różnica wobec [normalizePolishPhone] jest zamierzona i istotna: tamta funkcja przy
 * niepowodzeniu oddaje wejście bez zmian, bo służy do *wyświetlania i wysyłki*. Ta służy
 * do *porównywania* — a klucz dopasowania, który przy śmieciu zwraca śmieć, sklejałby ze
 * sobą różnych ludzi („brak", „-", „telefon do żony"). Lepiej nie dopasować niczego.
 *
 * Reguły, w tej kolejności (muszą odpowiadać backfillowi w V99__customer_import.sql):
 *  1. numer z jawnym `+` i długością 8–15 cyfr — bierzemy jak jest,
 *  2. dziewięć cyfr — numer krajowy, dostaje `+48`,
 *  3. prefiks `00` — telefoniczny zapis międzynarodowy, zamieniany na `+`,
 *  4. jedenaście cyfr zaczynających się od `48` — polski numer bez plusa.
 *
 * Świadomie NIE zgadujemy niczego poza tym. Numer skrócony (infolinia), wewnętrzny czy
 * z rozszerzeniem nie jest kluczem, po którym wolno łączyć kartoteki.
 */
fun normalizeToE164(phone: String?): String? {
    val raw = phone?.trim().orEmpty()
    if (raw.isEmpty()) return null

    // Plus ma znaczenie tylko na początku: „+48 (61) 123-45-67" to wciąż jeden numer.
    val hasPlus = raw.startsWith("+")
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return null

    return when {
        hasPlus && digits.length in 8..15 -> "+$digits"
        digits.length == 9 -> "+48$digits"
        digits.startsWith("00") && digits.length in 10..17 -> "+${digits.substring(2)}"
        digits.length == 11 && digits.startsWith("48") -> "+$digits"
        else -> null
    }
}
