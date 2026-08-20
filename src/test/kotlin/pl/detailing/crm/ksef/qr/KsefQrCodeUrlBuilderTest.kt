package pl.detailing.crm.ksef.qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.ksef.config.KsefProperties
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64

/**
 * Kod QR na fakturze musi prowadzić dokładnie tam, gdzie oczekuje MF — literówka
 * w formacie daje kod, który po zeskanowaniu nie potwierdza niczego.
 */
class KsefQrCodeUrlBuilderTest {

    private val xml = "<Faktura>test</Faktura>"
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(xml.toByteArray())
    private val hashBase64 = Base64.getEncoder().encodeToString(sha256)
    private val hashBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256)

    private fun builder(baseUrl: String = "https://api.ksef.mf.gov.pl", qrBaseUrl: String? = null) =
        KsefQrCodeUrlBuilder(KsefProperties(baseUrl = baseUrl, qrBaseUrl = qrBaseUrl))

    @Test
    fun `buduje adres weryfikacyjny w formacie KOD I`() {
        val url = builder().buildInvoiceVerificationUrl("7773455203", LocalDate.of(2026, 2, 1), hashBase64)

        assertEquals("https://qr.ksef.mf.gov.pl/invoice/7773455203/01-02-2026/$hashBase64Url", url)
    }

    @Test
    fun `host kodow QR idzie za srodowiskiem API`() {
        val url = builder(baseUrl = "https://api-test.ksef.mf.gov.pl")
            .buildInvoiceVerificationUrl("7773455203", LocalDate.of(2026, 2, 1), hashBase64)

        assertEquals("https://qr-test.ksef.mf.gov.pl/invoice/7773455203/01-02-2026/$hashBase64Url", url)
    }

    @Test
    fun `jawnie ustawiony host kodow QR ma pierwszenstwo`() {
        val url = builder(qrBaseUrl = "https://qr-demo.ksef.mf.gov.pl/")
            .buildInvoiceVerificationUrl("7773455203", LocalDate.of(2026, 2, 1), hashBase64)

        assertEquals("https://qr-demo.ksef.mf.gov.pl/invoice/7773455203/01-02-2026/$hashBase64Url", url)
    }

    @Test
    fun `NIP z separatorami sprowadza sie do samych cyfr`() {
        val url = builder().buildInvoiceVerificationUrl("777-345-52-03", LocalDate.of(2026, 2, 1), hashBase64)

        assertEquals("https://qr.ksef.mf.gov.pl/invoice/7773455203/01-02-2026/$hashBase64Url", url)
    }

    @Test
    fun `skrot juz zapisany w Base64URL przechodzi bez zmian`() {
        val url = builder().buildInvoiceVerificationUrl("7773455203", LocalDate.of(2026, 2, 1), hashBase64Url)

        assertEquals("https://qr.ksef.mf.gov.pl/invoice/7773455203/01-02-2026/$hashBase64Url", url)
    }

    @Test
    fun `skrot liczony z XML zgadza sie ze skrotem z KSeF`() {
        assertEquals(hashBase64Url, builder().hashInvoiceXml(xml))
    }

    @Test
    fun `brak skladnika daje brak adresu zamiast kodu prowadzacego donikad`() {
        val b = builder()
        val date = LocalDate.of(2026, 2, 1)

        assertNull(b.buildInvoiceVerificationUrl(null, date, hashBase64))
        assertNull(b.buildInvoiceVerificationUrl("7773455203", null, hashBase64))
        assertNull(b.buildInvoiceVerificationUrl("7773455203", date, null))
        assertNull(b.buildInvoiceVerificationUrl("7773455203", date, "   "))
        assertNull(b.buildInvoiceVerificationUrl("7773455203", date, "nie-jest-skrotem"))
    }
}
