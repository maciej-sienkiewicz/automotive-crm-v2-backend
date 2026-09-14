package pl.detailing.crm.instagram.ads.discovery

import pl.detailing.crm.instagram.ads.RawAdLocation

/**
 * Czy targetowanie reklamy obejmuje wskazany rejon.
 *
 * Wejście: `target_locations` reklamy (nazwa, typ, czy wykluczona) tak, jak
 * zwraca je Meta, oraz lista miejscowości wpisanych przez studio i tryb.
 *
 * Ocena jest ZAWSZE per miejscowość, a reklama pasuje, gdy trafia w CHOĆ JEDNĄ.
 * To jedyny sposób, żeby poprawnie obsłużyć wykluczenia: reklama na całą Polskę
 * z wykluczonym Poznaniem nie trafia w zapytanie o sam Poznań, ale trafia w
 * zapytanie o Poznań i Suchy Las (przez Suchy Las).
 *
 * Reguła dla jednej miejscowości C:
 *   - wykluczona wprost (C na liście wykluczeń) → NIE, niezależnie od reszty;
 *   - C w obszarach włączonych (dopasowanie po nazwie, dowolny typ) → TAK;
 *   - w trybie [AreaMatchMode.CITIES_ONLY] to koniec — brak dopasowania to NIE;
 *   - w trybie [AreaMatchMode.INCLUDE_BROADER] dodatkowo TAK, gdy reklama celuje
 *     w całą Polskę albo w województwo, w którym leży C (o ile znamy je ze słownika).
 *
 * Dopasowanie po województwie jest z definicji bezpieczne w jedną stronę: bez
 * wpisu w [PolishGeo] miejscowość nie łapie po województwie, ale nigdy nie łapie
 * błędnie cudzego (targetowanie na Mazowsze nie trafi w zapytanie o Poznań).
 */
object AreaLocationMatcher {

    fun matches(adLocations: List<RawAdLocation>, requestedCities: List<String>, mode: AreaMatchMode): Boolean {
        val requested = requestedCities.map(PolishGeo::normalize).filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty()) return false

        val included = adLocations.filter { !it.excluded }
        val excluded = adLocations.filter { it.excluded }.map { PolishGeo.normalize(it.name) }.toSet()

        return requested.any { city -> reaches(city, included, excluded, mode) }
    }

    private fun reaches(
        city: String,
        included: List<RawAdLocation>,
        excludedNames: Set<String>,
        mode: AreaMatchMode
    ): Boolean {
        // Wykluczenie samej miejscowości ubija ją niezależnie od szerszych obszarów.
        if (city in excludedNames) return false

        // Dopasowanie po nazwie — miejscowość wprost w targetowaniu.
        if (included.any { PolishGeo.normalize(it.name) == city }) return true

        if (mode == AreaMatchMode.CITIES_ONLY) return false

        // Cała Polska obejmuje każdą polską miejscowość (i tak pytamy tylko o PL).
        if (included.any { PolishGeo.isCountry(it.name) }) return true

        // Województwo miejscowości — o ile mamy je w słowniku i reklama w nie celuje.
        val voivodeship = PolishGeo.voivodeshipOf(city) ?: return false
        return included.any { PolishGeo.voivodeshipFromName(it.name) == voivodeship }
    }
}
