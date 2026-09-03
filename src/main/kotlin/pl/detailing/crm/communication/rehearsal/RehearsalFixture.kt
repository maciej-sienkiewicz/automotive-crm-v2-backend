package pl.detailing.crm.communication.rehearsal

import pl.detailing.crm.communication.template.MessageTemplateKind
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One fictional customer for the template rehearsal: Jan Kowalski, an Audi RS6, a ceramic
 * coating booked for tomorrow at 10:00.
 *
 * The values are chosen to trip the mistakes that hide in a plain preview:
 *  - a model and a plate with spaces (line breaking in SMS),
 *  - Polish letters and a hard space in the amount (GSM-7 vs UCS-2 billing; ECO strips diacritics),
 *  - tomorrow's date in Europe/Warsaw (a UTC server shows the hour drift immediately).
 *
 * [RehearsalFixtureTest] proves this map covers every placeholder of every
 * [MessageTemplateKind], so adding a placeholder to the enum without adding sample data
 * fails the build instead of the rehearsal.
 */
object RehearsalFixture {

    private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

    const val CUSTOMER_PHONE = "+48000000000"
    const val CUSTOMER_EMAIL = "jan.kowalski@example.invalid"
    const val CARD_LINK = "https://detailboost.pl/karta/PROBA-GENERALNA"

    fun tomorrowTen(today: LocalDate = LocalDate.now(WARSAW)): Instant =
        today.plusDays(1).atTime(10, 0).atZone(WARSAW).toInstant()

    fun allValues(seq: Int, moment: Instant = tomorrowTen()): Map<String, String> = mapOf(
        "imie" to "Jan",
        "nazwisko" to "Kowalski",
        "imie_nazwisko" to "Jan Kowalski",
        "pojazd" to "Audi RS6 Avant",
        "rejestracja" to "WE 4RS6X",
        "numer_wizyty" to "WIZ/2026/09/%03d".format(seq),
        "link" to CARD_LINK,
        "uslugi" to "Powłoka ceramiczna 9H, korekta lakieru 2-etapowa",
        "kwota" to "4 900,00 zł",
        "dokument" to "Protokół przyjęcia pojazdu",
        "kontrahent" to "Flota Premium Sp. z o.o.",
        "okres" to "sierpień 2026",
        "kwota_brutto" to "18 450,00 zł",
        "liczba_wpisow" to "7",
        "marka" to "Audi",
        "model" to "RS6 Avant",
        "ostatnia_usluga" to "Powłoka ceramiczna",
        "data_ostatniej_wizyty" to "12.03.2026",
        "dni_od_wizyty" to "175"
    ) + MessageTemplateRenderer.scheduleValues(moment)

    /** Exactly the placeholders this kind may use — nothing the renderer would not accept. */
    fun values(kind: MessageTemplateKind, seq: Int, moment: Instant = tomorrowTen()): Map<String, String> =
        allValues(seq, moment).filterKeys { it in kind.allowedPlaceholders }
}
