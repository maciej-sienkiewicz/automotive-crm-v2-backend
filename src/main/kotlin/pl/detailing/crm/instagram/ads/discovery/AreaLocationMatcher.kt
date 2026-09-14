package pl.detailing.crm.instagram.ads.discovery

import pl.detailing.crm.instagram.ads.RawAdLocation

/**
 * Czy targetowanie reklamy obejmuje wskazany rejon.
 *
 * Wejście: `target_locations` reklamy (nazwa, typ, czy wykluczona) tak, jak
 * zwraca je Meta, oraz lista miejscowości wpisanych przez studio i tryb.
 *
 * Format nazw z Meta: miasto przychodzi z doklejonym krajem, a bywa i kilka
 * miejsc w jednym wpisie, np. „Poznań, Polska" albo „Kleszczewo, Poznań, Poland,
 * Polska". Miejscowość dopasowujemy więc przez **contains** po znormalizowanej
 * nazwie — trafia niezależnie od tego, na której pozycji stoi. Świadomie godzimy
 * się na drobny „przeciek" (np. „Krakowiany" złapie zapytanie o „Kraków", a „Poznań
 * County" złapie „Poznań") — decyzja produktowa: wolimy nadmiar niż zgubić lokalnego
 * konkurenta ukrytego w złożonej nazwie.
 *
 * UWAGA na kraj: kontrola „czy to cała Polska" idzie po PEŁNEJ nazwie, nie po
 * `contains` ani po segmentach — inaczej „Warszawa, Polska" (miasto) udawałoby
 * targetowanie na kraj i reklamy obcych miast wpadałyby do każdego zapytania.
 *
 * Ocena jest ZAWSZE per miejscowość, a reklama pasuje, gdy trafia w CHOĆ JEDNĄ —
 * to jedyny sposób na poprawne wykluczenia (reklama na Polskę bez Poznania trafia
 * w zapytanie o „Poznań i Suchy Las" przez Suchy Las, ale nie w samo „Poznań").
 *
 * Reguła dla jednej miejscowości C:
 *   - wykluczona wprost (nazwa którejś wykluczonej lokalizacji zawiera C) → NIE;
 *   - C zawarte w którejś nazwie włączonej (dowolny typ) → TAK;
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
        // Wykluczenie miejscowości ubija ją niezależnie od szerszych obszarów.
        if (excluded.any { nameContainsCity(it.name, city) }) return false

        // Miejscowość zawarta w nazwie targetu (np. „Poznań, Polska", „Poznań County").
        if (included.any { nameContainsCity(it.name, city) }) return true

        if (mode == AreaMatchMode.CITIES_ONLY) return false

        // Cała Polska — PEŁNA nazwa, żeby „X, Polska" (miasto) nie udawało kraju.
        if (included.any { PolishGeo.isCountry(it.name) }) return true

        // Województwo miejscowości — o ile mamy je w słowniku i reklama w nie celuje.
        val voivodeship = PolishGeo.voivodeshipOf(city) ?: return false
        return included.any { loc -> loc.name.split(',').any { PolishGeo.voivodeshipFromName(it) == voivodeship } }
    }

    /**
     * Czy znormalizowana nazwa lokalizacji zawiera nazwę miejscowości.
     *
     * Świadomie zwykły `contains`, nie równość segmentów — łapie miasto na dowolnej
     * pozycji ORAZ wtopione w nazwę złożoną („Poznań County"). Ceną jest przeciek na
     * podłańcuchach, na który się godzimy.
     */
    private fun nameContainsCity(locationName: String, normalizedCity: String): Boolean =
        PolishGeo.normalize(locationName).contains(normalizedCity)
}
