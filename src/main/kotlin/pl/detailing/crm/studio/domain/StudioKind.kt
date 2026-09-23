package pl.detailing.crm.studio.domain

/**
 * Rodzaj studia. Nadawany raz, przy zakładaniu, i nigdy potem nie zmieniany - od niego
 * zależy, czy konta studia mogą się logować i czy studio może cokolwiek wysłać na zewnątrz.
 */
enum class StudioKind {
    /** Studio klienta. */
    REGULAR,

    /** Konto DEMO z ekranu logowania (dwugodzinne, publiczne). */
    DEMO,

    /**
     * Piaskownica podglądu roli: jednorazowe studio z danymi przykładowymi, w którym
     * administrator ogląda CRM oczami pracownika z daną rolą. Konta piaskownicy nie
     * logują się niczym poza jednorazowym kodem wejścia, a nic, co dzieje się w
     * piaskownicy, nie wychodzi poza nasz system (SMS, e-mail, push, KSeF, płatności...).
     */
    ROLE_PREVIEW
}
