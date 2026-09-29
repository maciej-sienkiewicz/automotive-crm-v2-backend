package pl.detailing.crm.ksef.transfer

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.shared.qr.QrCodeImageFactory
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Kod z faktury kosztowej, odczytany z powrotem z PNG tak, jak zrobi to aplikacja banku.
 * Dekodowanie, a nie porównanie z oczekiwanym obrazkiem: tylko ono pokazuje, że polskie
 * litery przeżyły kodowanie (UTF-8) i że kwota jest tą z faktury.
 */
class BankQrCodeGeneratorTest {

    private val generator = BankQrCodeGenerator(QrCodeImageFactory())

    private fun expense(
        grossAmount: Long? = 190_000,
        netAmount: Long? = 154_472,
        bankAccount: String? = "PL61 1090 1014 0000 0712 1981 2874",
        sellerName: String? = "Hurtownia Chemii Łódź Sp. z o.o.",
        sellerNip: String? = "526-025-02-74",
        invoiceNumber: String? = "FV/12/2026",
        currency: String? = "PLN",
        status: String = "ACTIVE",
        paymentStatus: String = "PENDING",
    ) = KsefInvoiceEntity(
        studioId = UUID.randomUUID(),
        ksefNumber = "5260250274-20260929-ABCDEF-01",
        invoiceNumber = invoiceNumber,
        invoicingDate = null,
        issueDate = null,
        sellerNip = sellerNip,
        sellerName = sellerName,
        buyerNip = null,
        buyerName = null,
        netAmount = netAmount,
        grossAmount = grossAmount,
        vatAmount = grossAmount?.let { g -> netAmount?.let { g - it } },
        currency = currency,
        invoiceType = "VAT",
        status = status,
        paymentStatus = paymentStatus,
        bankAccount = bankAccount,
    )

    private fun decode(base64: String): Pair<String, String?> {
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(base64)))
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        val result = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
        return result.text to result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL] as String?
    }

    @Test
    fun `aplikacja banku odczyta z kodu rachunek, kwote brutto z faktury i polskie litery`() {
        val transfer = generator.generate(expense())

        val png = transfer.qrPngBase64
        assertNotNull(png, transfer.qrUnavailableReason)
        val (text, ecc) = decode(png!!)
        assertEquals("5260250274|PL|61109010140000071219812874|190000|Hurtownia Chemii Łód|Faktura FV/12/2026|||", text)
        assertEquals("L", ecc, "rekomendacja ZBP: korekcja błędów L")
        assertNull(transfer.qrUnavailableReason)

        // Na ekranie pełna nazwa i znormalizowany rachunek - w kodzie nazwa ucięta do 20 znaków.
        assertEquals("Hurtownia Chemii Łódź Sp. z o.o.", transfer.recipientName)
        assertEquals("61109010140000071219812874", transfer.accountNumber)
        assertEquals(190_000L, transfer.amountGrosze)
        assertEquals("Faktura FV/12/2026", transfer.title)
    }

    @Test
    fun `bledny NIP nie blokuje kodu - pole jest opcjonalne, wiec zostaje puste`() {
        val transfer = generator.generate(expense(sellerNip = "1234567890"))
        val (text, _) = decode(transfer.qrPngBase64!!)
        assertTrue(text.startsWith("|PL|61109010140000071219812874|"), text)
    }

    @Test
    fun `kwota ponad pole ZBP - bez kodu, ale dane do przelewu zostaja`() {
        val transfer = generator.generate(expense(grossAmount = 1_230_000, netAmount = 1_000_000))
        assertNull(transfer.qrPngBase64)
        assertTrue(transfer.qrUnavailableReason!!.contains("9 999,99 zł"))
        assertEquals("61109010140000071219812874", transfer.accountNumber)
        assertEquals(1_230_000L, transfer.amountGrosze)
    }

    @Test
    fun `rachunek z bledna suma kontrolna - bez kodu, numer z faktury pokazany jako niepewny`() {
        val transfer = generator.generate(expense(bankAccount = "61 1090 1014 0000 0712 1981 2875"))
        assertNull(transfer.qrPngBase64)
        assertFalse(transfer.accountNumberValid)
        assertEquals("61 1090 1014 0000 0712 1981 2875", transfer.accountNumber)
        assertTrue(transfer.qrUnavailableReason!!.contains("nie jest poprawnym polskim numerem"))
    }

    @Test
    fun `faktura oplacona, w euro, bez rachunku, anulowana albo skorygowana nie dostaje kodu`() {
        val cases = mapOf(
            "opłacona" to expense(paymentStatus = "PAID"),
            "w złotych" to expense(currency = "EUR"),
            "nie ma numeru rachunku" to expense(bankAccount = null),
            "anulowana" to expense(status = "CANCELLED"),
            "korektę" to expense(status = "CORRECTED"),
            "nie ma kwoty" to expense(grossAmount = null),
            "nie ma nazwy sprzedawcy" to expense(sellerName = "\"|\""),
        )
        cases.forEach { (fragment, invoice) ->
            val transfer = generator.generate(invoice)
            assertNull(transfer.qrPngBase64, fragment)
            assertTrue(transfer.qrUnavailableReason!!.contains(fragment), "$fragment: ${transfer.qrUnavailableReason}")
        }
    }
}
