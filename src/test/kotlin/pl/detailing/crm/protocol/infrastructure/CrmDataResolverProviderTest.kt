package pl.detailing.crm.protocol.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ramka USŁUGODAWCA: nazwa firmy z adresem siedziby, bez pustych fragmentów. */
class CrmDataResolverProviderTest {

    @Test
    fun `nazwa z pelnym adresem`() {
        assertEquals(
            "Bellissimoto Detailing, ul. Puławska 145, 02-715 Warszawa",
            CrmDataResolver.providerLine("Bellissimoto Detailing", "ul. Puławska 145", "02-715", "Warszawa")
        )
    }

    @Test
    fun `brakujace czesci adresu wypadaja z listy`() {
        assertEquals("Studio X, Kraków", CrmDataResolver.providerLine("Studio X", null, "  ", "Kraków"))
        assertEquals("Studio X, ul. Długa 1", CrmDataResolver.providerLine("Studio X", "ul. Długa 1", null, null))
        assertEquals("Studio X", CrmDataResolver.providerLine("Studio X", "", "", ""))
    }

    @Test
    fun `biale znaki sa przycinane`() {
        assertEquals("Studio X, ul. Długa 1, 00-001 Kraków", CrmDataResolver.providerLine(" Studio X ", " ul. Długa 1 ", " 00-001", "Kraków "))
    }
}
