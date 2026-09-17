package pl.detailing.crm.product.application

import org.springframework.stereotype.Component
import pl.detailing.crm.product.PriceInput
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate

/**
 * Rozwiązuje parę cenową zgodnie z regułą brutto z CLAUDE.md §1.
 *
 * Kwota, którą wpisał człowiek, JEST źródłem prawdy i nie wolno jej policzyć po raz
 * drugi. Druga strona wynika z niej, liczona RAZ tutaj przez [VatRate]:
 *  - wpis NET  → brutto = vatRate.resolveGrossAmount(net, providedGross)
 *  - wpis GROSS→ netto  = vatRate.netCentsFromGrossCents(gross)
 *
 * To jedyne miejsce w module, które dotyka arytmetyki VAT, i deleguje ją w całości do
 * współdzielonego [VatRate] — moduł nie pisze własnej.
 */
@Component
class ProductPriceResolver {

    data class ResolvedPrice(
        val netCents: Long,
        val grossCents: Long,
        val enteredAs: String,
        val vatRate: Int
    )

    fun resolve(input: PriceInput?): ResolvedPrice? {
        if (input == null) return null
        val vat = VatRate.fromInt(input.vatRate)
        val direction = input.priceEnteredAs.trim().uppercase()

        return when (direction) {
            "NET" -> {
                val net = input.unitPriceNet
                    ?: throw ValidationException("Cena wpisana jako netto wymaga wartości netto.")
                if (net < 0) throw ValidationException("Cena nie może być ujemna.")
                val netMoney = Money.fromCents(net)
                // providedGross to dokładna kwota z formularza, gdy front ją zna; inaczej pochodna.
                val gross = vat.resolveGrossAmount(
                    netMoney,
                    input.unitPriceGross?.let { Money.fromCents(it) }
                )
                ResolvedPrice(net, gross.amountInCents, "NET", input.vatRate)
            }
            "GROSS" -> {
                val gross = input.unitPriceGross
                    ?: throw ValidationException("Cena wpisana jako brutto wymaga wartości brutto.")
                if (gross < 0) throw ValidationException("Cena nie może być ujemna.")
                // Brutto podane przez człowieka WYGRYWA i jest zapisywane bez zmian;
                // netto z niego wynika (grossToNet), co jest zawsze w porządku.
                val net = vat.netCentsFromGrossCents(gross)
                ResolvedPrice(net, gross, "GROSS", input.vatRate)
            }
            else -> throw ValidationException("Kierunek ceny musi być NET albo GROSS.")
        }
    }
}
