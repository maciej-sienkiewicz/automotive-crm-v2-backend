package pl.detailing.crm.appointment.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.create.CreateAppointmentValidationContext
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.ValidationException

/**
 * Usługa z ceną ustalaną ręcznie musi przyjechać z ceną.
 *
 * Cennik nie ma dla niej żadnej kwoty (zapisane jest przy niej `Money.ZERO`), więc
 * pozycja bez ceny w żądaniu kończy się rezerwacją za 0 zł - i nikt się o tym nie
 * dowiaduje, dopóki ktoś nie otworzy wizyty i nie zobaczy „Cena z cennika: 0,00 zł".
 * Cichy zapis złej kwoty jest gorszy od odmowy, dlatego to jest błąd żądania.
 *
 * Cena jest uznana za podaną, gdy pozycja niesie ją w KTÓRYMKOLWIEK z dwóch
 * kształtów, których używają nasi klienci:
 *  - kwota docelowa w rabacie `SET_NET` / `SET_GROSS` (przyjęcie pojazdu, edycja
 *    rezerwacji - patrz `toApiServiceLineItem` po stronie frontendu),
 *  - kwota wprost w `basePriceNet` albo `basePriceGross` (kreator wizyty).
 *
 * Wcześniejsza wersja uznawała wyłącznie pierwszy kształt - i nie była nigdzie
 * podpięta, więc przez cały czas nie sprawdzała niczego. To przez to kreator wizyty
 * mógł bez słowa zapisać usługę wycenioną na 5000 zł jako pozycję za zero.
 */
@Component
class ManualPriceRequiredValidator {
    fun validate(context: CreateAppointmentValidationContext) {
        val servicesById = context.services.associateBy { it.id }

        val withoutPrice = context.requestedServiceLineItems.filter { lineItem ->
            val service = servicesById[lineItem.serviceId] ?: return@filter false
            if (!service.requireManualPrice) return@filter false

            val setsTargetPrice =
                (lineItem.adjustmentType == AdjustmentType.SET_NET ||
                    lineItem.adjustmentType == AdjustmentType.SET_GROSS) &&
                    lineItem.adjustmentValue != 0.0
            val carriesBasePrice =
                lineItem.basePriceNet > 0 || (lineItem.basePriceGross ?: 0L) > 0L

            !setsTargetPrice && !carriesBasePrice
        }

        if (withoutPrice.isNotEmpty()) {
            val serviceNames = withoutPrice
                .mapNotNull { lineItem -> servicesById[lineItem.serviceId]?.name }
                .joinToString(", ")

            throw ValidationException(
                "Następujące usługi wymagają ręcznego podania ceny: $serviceNames. " +
                "Proszę podać jawną cenę dla każdej usługi."
            )
        }
    }
}
