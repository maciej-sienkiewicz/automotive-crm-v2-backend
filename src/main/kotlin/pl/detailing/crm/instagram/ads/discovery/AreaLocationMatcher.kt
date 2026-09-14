package pl.detailing.crm.instagram.ads.discovery

import pl.detailing.crm.instagram.ads.RawAdLocation

/**
 * Czy targetowanie reklamy obejmuje wskazany rejon.
 *
 * Wejście: `target_locations` reklamy (nazwa, typ, czy wykluczona) tak, jak
 * zwraca je Meta, oraz lista miejscowości wpisanych przez studio i tryb.
 *
 * KLUCZOWE o formacie nazw z Meta: nazwa miasta przychodzi z doklejonym krajem,
 * a bywa i kilka miejsc w jednym wpisie, rozdzielonych przecinkiem:
 *   „Poznań, Polska"
 *   „Kleszczewo, Poznań, Poland, Polska"
 * Dlatego miasto dopasowujemy do KTÓREGOKOLWIEK SEGMENTU nazwy po przecinku, a nie
 * do całości — inaczej „poznan" nigdy nie zrówna się z „poznan polska" i miasta
 * przepadają (a tak właśnie było).
 *
 * Ocena jest ZAWSZE per miejscowość, a reklama pasuje, gdy trafia w CHOĆ JEDNĄ.
 * To jedyny sposób, żeby poprawnie obsłużyć wykluczenia: reklama na całą Polskę
 * z wykluczonym Poznaniem nie trafia w zapytanie o sam Poznań, ale trafia w
 * zapytanie o Poznań i Suchy Las (przez Suchy Las).
 *
 * Reguła dla jednej miejscowości C:
 *   - wykluczona wprost (C jako segment którejś nazwy wykluczonej) → NIE;
 *   - C w obszarach włączonych (C jako segment nazwy, dowolny typ) → TAK;
 *   - w trybie [AreaMatchMode.CITIES_ONLY] to koniec — brak dopasowania to NIE;
 *   - w trybie [AreaMatchMode.INCLUDE_BROADER] dodatkowo TAK, gdy reklama celuje
 *     w całą Polskę albo w województwo, w którym leży C (o ile znamy je ze słownika).
 */
object AreaLocationMatcher {

    fun matches(adLocations: List<RawAdLocation>, requestedCities: List<String>, mode: AreaMatchMode): Boolean {
        val requested = requestedCities.map(PolishGeo::normalize).filter { it.isNotBlank() }.distinct()
        if (requested.isEmpty()) return false

        val included = adLocations.filter { !it.excluded }
        val excluded = adLocations.filter { it.excluded }

        return requested.any { city -> reaches(city, included, excluded, mode) }
    }

    private fun reaches(
        city: String,
        included: List<RawAdLocation>,
        excluded: List<RawAdLocation>,
        mode: AreaMatchMode
    ): Boolean {
        // Wykluczenie samej miejscowości ubija ją niezależnie od szerszych obszarów.
        if (excluded.any { nameHasCity(it.name, city) }) return false

        // Dopasowanie po nazwie — miejscowość jako segment targetu (np. „Poznań, Polska").
        if (included.any { nameHasCity(it.name, city) }) return true

        if (mode == AreaMatchMode.CITIES_ONLY) return false

        // Cała Polska obejmuje każdą polską miejscowość (i tak pytamy tylko o PL).
        if (included.any { nameSegments(it.name).any(PolishGeo::isCountry) }) return true

        // Województwo miejscowości — o ile mamy je w słowniku i reklama w nie celuje.
        val voivodeship = PolishGeo.voivodeshipOf(city) ?: return false
        return included.any { loc -> nameSegments(loc.name).any { PolishGeo.voivodeshipFromName(it) == voivodeship } }
    }

    /** Czy któryś segment nazwy (po przecinku) to dokładnie ta miejscowość. */
    private fun nameHasCity(locationName: String, normalizedCity: String): Boolean =
        nameSegments(locationName).any { PolishGeo.normalize(it) == normalizedCity }

    /** „Kleszczewo, Poznań, Poland, Polska" → [„Kleszczewo", „ Poznań", „ Poland", „ Polska"]. */
    private fun nameSegments(locationName: String): List<String> = locationName.split(',')
}
