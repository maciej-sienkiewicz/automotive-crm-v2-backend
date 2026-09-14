package pl.detailing.crm.instagram.ads.discovery

/**
 * Fraza jako klucz wspólnego cache.
 *
 * Normalizujemy do wspólnej postaci, żeby „Detailing", „detailing " i „DETAILING"
 * trafiały w jeden wpis — inaczej cache dzielony między najemcami rozjechałby się
 * na warianty tego samego hasła i każdy odpalałby osobne pobranie z Meta.
 *
 * Zachowujemy polskie znaki: `search_terms` idzie do Meta w tej postaci, a „świeży"
 * bez ogonków to inne wyszukiwanie niż „świeży". Zbijamy tylko wielkość liter i
 * białe znaki — na tym Meta i tak nie robi różnicy.
 */
object AdDiscoveryPhrase {

    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 100

    fun normalize(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Znormalizowana fraza spełniająca ograniczenia długości albo null. */
    fun normalizeValid(raw: String): String? =
        normalize(raw).takeIf { it.length in MIN_LENGTH..MAX_LENGTH }
}
