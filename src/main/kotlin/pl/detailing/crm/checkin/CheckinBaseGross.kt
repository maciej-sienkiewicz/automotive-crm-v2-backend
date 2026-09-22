package pl.detailing.crm.checkin

import pl.detailing.crm.appointment.domain.AppointmentLineItem
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import java.util.UUID

/** Cena katalogowa usługi — para netto/brutto zapisana w cenniku. */
internal data class CatalogPrice(val basePriceNet: Long, val basePriceGross: Long, val vatRate: Int)

/**
 * Dokładne brutto ceny bazowej usługi przyjmowanej na check-inie (CLAUDE.md §1),
 * od najpewniejszego źródła:
 *  1. brutto przysłane przez klienta API — wpisane w kreatorze albo przeniesione z rezerwacji,
 *  2. brutto zapisane przy tej usłudze w rezerwacji, o ile cena bazowa i stawka się nie zmieniły,
 *  3. brutto z katalogu, o ile cena bazowa jest ceną katalogową.
 * `null` = cena od strony netta.
 *
 * Klienty API sprzed poprawki kreatora nie wysyłały brutto, a check-in odtwarzał je wtedy
 * z netta: usługa za 1900,00 zł trafiała na wizytę, paragon i fakturę jako 1900,01 zł.
 *
 * Brutto od klienta, które odbiega od netta o więcej niż grosz zaokrąglenia „w stu",
 * to niespójne dane — dostają 400 zamiast wybuchnąć niezmiennikiem pozycji (500).
 */
internal fun resolveCheckinBaseGross(
    serviceReq: ServiceLineItemRequest,
    reservationItems: List<AppointmentLineItem>,
    catalog: Map<UUID, CatalogPrice>
): Money? {
    val vatRate = VatRate.fromInt(serviceReq.vatRate)

    serviceReq.basePriceGross?.let { gross ->
        val derived = vatRate.calculateGrossAmount(Money.fromCents(serviceReq.basePriceNet)).amountInCents
        if (gross < 0 || Math.abs(gross - derived) > 1) {
            throw ValidationException(
                "Niespójna cena usługi „${serviceReq.serviceName}”: brutto $gross gr nie odpowiada " +
                    "netto ${serviceReq.basePriceNet} gr przy stawce ${serviceReq.vatRate}%"
            )
        }
        return Money.fromCents(gross)
    }

    val serviceId = serviceReq.serviceId?.let { ServiceId.fromString(it) }
    reservationItems.firstOrNull {
        it.basePriceGross != null &&
            it.serviceId == serviceId &&
            it.serviceName == serviceReq.serviceName &&
            it.basePriceNet.amountInCents == serviceReq.basePriceNet &&
            it.vatRate == vatRate
    }?.let { return it.basePriceGross }

    return serviceId?.let { catalog[it.value] }
        ?.takeIf { it.basePriceNet == serviceReq.basePriceNet && it.vatRate == serviceReq.vatRate }
        ?.let { Money.fromCents(it.basePriceGross) }
}
