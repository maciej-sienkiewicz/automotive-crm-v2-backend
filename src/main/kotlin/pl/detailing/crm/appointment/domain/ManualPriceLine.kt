package pl.detailing.crm.appointment.domain

import pl.detailing.crm.shared.Money

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
