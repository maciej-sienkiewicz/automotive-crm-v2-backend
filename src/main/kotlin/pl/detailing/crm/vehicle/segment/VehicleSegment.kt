package pl.detailing.crm.vehicle.segment

/**
 * Segment wielkości wg klasyfikacji europejskiej.
 *
 * Dla studia detailingowego to nie jest ciekawostka motoryzacyjna, tylko miara
 * pracy i ceny: powierzchnia lakieru, liczba wykrojów folii, czas mycia i to,
 * czy auto w ogóle zmieści się na stanowisku.
 */
enum class VehicleSizeSegment(val label: String) {
    A("Miejskie (A)"),
    B("Małe (B)"),
    C("Kompakt (C)"),
    D("Klasa średnia (D)"),
    E("Klasa wyższa (E)"),
    F("Limuzyny (F)"),
    SUV("SUV / crossover"),
    VAN("Van / minivan"),
    SPORT("Sportowe"),
    UNKNOWN("Nieznany");

    companion object {
        fun from(raw: String?): VehicleSizeSegment =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * Klasa rynkowa marki — o niej decyduje producent, nie rozmiar auta.
 *
 * To jest oś, na której realnie rozstrzyga się rozmowa o cenie: właściciel Dacii
 * i właściciel Porsche mogą przyjechać tym samym segmentem C, a rozmawiać o zupełnie
 * innych kwotach i zupełnie inaczej reagować na wycenę.
 */
enum class VehicleMarketTier(val label: String) {
    BUDGET("Budżetowe"),
    MAINSTREAM("Popularne"),
    PREMIUM("Premium"),
    LUXURY("Luksusowe"),
    UNKNOWN("Nieznana");

    companion object {
        fun from(raw: String?): VehicleMarketTier =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}
