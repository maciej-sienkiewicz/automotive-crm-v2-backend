package pl.detailing.crm.appointment.domain

import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VatRate

/**
 * Cena bazowa pozycji opartej o usługę z CENNIKA.
 *
 * Zasadą jest, że cenę usługi katalogowej dyktuje cennik, a nie klient HTTP -
 * inaczej dowolne okno mogłoby po cichu sprzedać usługę taniej, niż ustalono.
 * Od tej zasady jest JEDEN wyjątek i jest nim usługa z `requireManualPrice`.
 *
 * Taka usługa NIE MA ceny w cenniku i nie jest to przeoczenie: `CreateServiceHandler`
 * i `UpdateServiceHandler` zapisują przy niej `Money.ZERO` i celowo odrzucają kwotę
 * przysłaną przy jej tworzeniu („manual-price services must not carry a catalog
 * price"). Jedynym miejscem, w którym cena takiej pozycji w ogóle istnieje, jest
 * żądanie tworzące rezerwację. Wzięcie jej wtedy z cennika daje pozycję za 0 zł,
 * rezerwację z `totalNet = 0` i - po przyjęciu pojazdu - wizytę, na której nie da
 * się już policzyć rabatu, bo nie ma od czego.
 *
 * Klienci przysyłają tę cenę w dwóch kształtach i oba są poprawne:
 *  - `basePriceNet` wprost, z rabatem zerowym (kreator wizyty),
 *  - `basePriceNet = 0` plus rabat `SET_NET`/`SET_GROSS` z kwotą docelową
 *    (przyjęcie pojazdu i edycja rezerwacji - patrz `toApiServiceLineItem`
 *    po stronie frontendu).
 * Drugi kształt przechodzi przez tę funkcję bez zmian (zero zostaje zerem),
 * bo kwotę i tak narzuca rabat - a pierwszy przestaje gubić cenę.
 */
fun catalogBaseNet(requireManualPrice: Boolean, catalogNet: Money, requestedNetCents: Long): Money =
    if (requireManualPrice) Money.fromCents(requestedNetCents) else catalogNet

/**
 * Brutto bazowe pozycji katalogowej. Dla usługi z ceną ręczną cennik ma zero,
 * więc jedynym dokładnym bruttem jest to przysłane w żądaniu; `null` oznacza
 * „licz z netta" i jest poprawną odpowiedzią, gdy człowiek wpisał cenę netto.
 */
fun catalogBaseGross(requireManualPrice: Boolean, catalogGross: Money, requestedGrossCents: Long?): Money? =
    if (requireManualPrice) requestedGrossCents?.let { Money.fromCents(it) } else catalogGross

/**
 * Cena bazowa pozycji z cennika — netto i dokładne brutto — przy stawce VAT tej POZYCJI.
 *
 * Usługa z ceną ręczną bierze cenę z żądania ([catalogBaseNet], [catalogBaseGross]). Zwykła
 * bierze ją z cennika, ale brutto cennika obowiązuje wyłącznie przy stawce cennika. Pozycja
 * rezerwacji może mieć inną (stawkę zmienia tabela usług, a kalendarz wysyłał domyślne 23%):
 * brutto przekazane wtedy dalej nie pasowało do netta i kontrola spójności pozycji wywracała
 * cały zapis rezerwacji.
 *
 * Przy innej stawce zostaje strona, którą ktoś ustalił (CLAUDE.md §1): brutto wpisane od
 * strony brutto — różne od netto × stawka — zostaje, a netto liczy się z niego; w każdym
 * innym przypadku zostaje netto, a brutto liczy się z niego. To ta sama reguła co
 * `withVatRate` na froncie, więc podgląd w tabeli i zapis dają te same kwoty.
 */
fun catalogLinePrice(
    requireManualPrice: Boolean,
    catalogNet: Money,
    catalogGross: Money,
    catalogVatRate: VatRate,
    lineVatRate: VatRate,
    requestedNetCents: Long,
    requestedGrossCents: Long?
): Pair<Money, Money?> {
    if (requireManualPrice) {
        return catalogBaseNet(true, catalogNet, requestedNetCents) to
            catalogBaseGross(true, catalogGross, requestedGrossCents)
    }
    if (lineVatRate == catalogVatRate) return catalogNet to catalogGross
    val grossTyped = catalogGross != catalogVatRate.calculateGrossAmount(catalogNet)
    return if (grossTyped) {
        Money(lineVatRate.netCentsFromGrossCents(catalogGross.amountInCents)) to catalogGross
    } else {
        catalogNet to null
    }
}
