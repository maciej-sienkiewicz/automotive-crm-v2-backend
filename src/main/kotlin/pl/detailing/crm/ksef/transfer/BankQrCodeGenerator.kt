package pl.detailing.crm.ksef.transfer

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.springframework.stereotype.Component
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.shared.qr.QrCodeImageFactory
import java.util.Base64

/**
 * Dane do przelewu za fakturę kosztową i kod QR, który wpisze je w aplikacji banku.
 *
 * Dane do przelewu wracają zawsze, gdy da się je odczytać z faktury - także wtedy, gdy
 * kodu wystawić nie można (np. kwota ponad limit pola ZBP). Właściciel i tak musi
 * zapłacić, a przepisanie numeru z ekranu to dokładnie ten błąd, którego kod unika.
 * Powód braku kodu jest zdaniem dla człowieka, nie kodem błędu.
 *
 * Kwota to brutto z faktury, zapisane przy pobraniu z KSeF albo wpisane przez człowieka
 * (CLAUDE.md §1) - nigdy nie liczymy go od nowa z netto.
 */
@Component
class BankQrCodeGenerator(
    private val qrCodeImageFactory: QrCodeImageFactory,
) {

    companion object {
        /**
         * Parametry z rekomendacji ZBP: korekcja L i co najmniej 250 px. Ekran pokazuje kod
         * w 240 px CSS, więc 480 px bitmapy daje ostre moduły na ekranach 2x. Ramka
         * 4 modułów to strefa ciszy ze standardu QR - na ekranie nie ma marginesu kartki.
         */
        val SCREEN_SPEC = QrCodeImageFactory.Spec(
            sizePx = 480,
            errorCorrection = ErrorCorrectionLevel.L,
            quietZoneModules = 4,
        )
    }

    fun generate(expense: KsefInvoiceEntity): ExpenseTransfer {
        val nrb = ZbpTransferCode.normalizeNrb(expense.bankAccount)
        val nip = ZbpTransferCode.normalizeNip(expense.sellerNip)
        val currency = expense.currency?.uppercase() ?: "PLN"
        val title = ZbpTransferCode.title(expense.invoiceNumber)
        val recipientInCode = ZbpTransferCode.sanitizeText(expense.sellerName, ZbpTransferCode.RECIPIENT_NAME_MAX)

        val transfer = ExpenseTransfer(
            recipientName = expense.sellerName?.trim()?.takeIf { it.isNotEmpty() },
            recipientNip = nip,
            accountNumber = nrb ?: expense.bankAccount?.trim()?.takeIf { it.isNotEmpty() },
            accountNumberValid = nrb != null,
            amountGrosze = expense.grossAmount,
            currency = currency,
            title = title,
            qrPngBase64 = null,
            qrUnavailableReason = null,
        )

        val reason = unavailableReason(expense, currency, nrb, recipientInCode)
        if (reason != null) return transfer.copy(qrUnavailableReason = reason)

        val payload = ZbpTransferCode.payload(
            nip = nip,
            nrb = nrb!!,
            amountGrosze = expense.grossAmount!!,
            recipientName = recipientInCode,
            title = title,
        )
        val png = qrCodeImageFactory.png(payload, SCREEN_SPEC)
            ?: return transfer.copy(qrUnavailableReason = "Nie udało się wygenerować kodu QR. Skorzystaj z danych do przelewu.")
        return transfer.copy(qrPngBase64 = Base64.getEncoder().encodeToString(png))
    }

    private fun unavailableReason(
        expense: KsefInvoiceEntity,
        currency: String,
        nrb: String?,
        recipientInCode: String,
    ): String? {
        val gross = expense.grossAmount
        return when {
            expense.status == "CANCELLED" -> "Faktura jest anulowana, nie ma czego opłacić."
            // Kwota do zapłaty wynika z faktury razem z korektą - kod z kwotą pierwotną wprowadziłby w błąd.
            expense.status == "CORRECTED" -> "Faktura ma korektę. Kwotę do zapłaty ustal razem z fakturą korygującą."
            expense.paymentStatus == "PAID" -> "Faktura jest już opłacona."
            currency != "PLN" -> "Kod QR do przelewu obsługuje tylko płatności w złotych."
            expense.bankAccount.isNullOrBlank() -> "Na fakturze nie ma numeru rachunku sprzedawcy."
            nrb == null -> "Numer rachunku z faktury nie jest poprawnym polskim numerem rachunku. Sprawdź go z fakturą, zanim zlecisz przelew."
            gross == null || gross <= 0 -> "Na fakturze nie ma kwoty do zapłaty."
            gross > ZbpTransferCode.MAX_AMOUNT_GROSZE ->
                "Kod QR w standardzie banków mieści kwoty do 9 999,99 zł. Ten przelew zleć ręcznie, kopiując dane obok."
            recipientInCode.isEmpty() -> "Na fakturze nie ma nazwy sprzedawcy."
            else -> null
        }
    }
}

/** Dane do przelewu za fakturę kosztową; [qrPngBase64] null znaczy „bez kodu", powód w [qrUnavailableReason]. */
data class ExpenseTransfer(
    /** Pełna nazwa sprzedawcy - w kodzie jest ucięta do 20 znaków, na ekranie nie musi. */
    val recipientName: String?,
    val recipientNip: String?,
    /** 26 cyfr NRB, gdy numer jest poprawny; inaczej numer tak, jak stoi na fakturze. */
    val accountNumber: String?,
    val accountNumberValid: Boolean,
    val amountGrosze: Long?,
    val currency: String,
    /** Tytuł dokładnie taki jak w kodzie. */
    val title: String,
    val qrPngBase64: String?,
    val qrUnavailableReason: String?,
)
