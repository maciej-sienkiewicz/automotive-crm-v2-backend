package pl.detailing.crm.instagram.ads.discovery.ig

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tłumaczenie odpowiedzi sidecara na wynik, którym da się sterować alertem.
 *
 * Sedno: NIGDY nie zwrócić nazwy, której nie jesteśmy pewni. Uchwyt Facebooka
 * pokazany właścicielowi studia jako „profil konkurenta na Instagramie" jest
 * gorszy niż puste pole — puste pole widać, a błędne dane wyglądają jak dane.
 */
class MetaIgResolverClientTest {

    private val mapper = ObjectMapper()

    private fun client(enabled: Boolean = true) =
        MetaIgResolverClient(mapper, enabled, "http://resolver:8080", 60)

    /** Dostęp do prywatnego parsera — testujemy tłumaczenie, nie HTTP. */
    private fun parse(json: String): IgLookupResult {
        val method = MetaIgResolverClient::class.java
            .getDeclaredMethod("parse", String::class.java)
            .apply { isAccessible = true }
        return method.invoke(client(), json) as IgLookupResult
    }

    @Test
    fun `odczytana nazwa wraca jako OK`() {
        val result = parse("""{"status":"ok","igUsername":"pro_garage_performance"}""")

        assertEquals(IgLookupStatus.OK, result.status)
        assertEquals("pro_garage_performance", result.igUsername)
    }

    /** Małpa bywa w danych; w bazie trzymamy samą nazwę. */
    @Test
    fun `malpa i biale znaki odpadaja`() {
        assertEquals("autospa_poznan", parse("""{"status":"ok","igUsername":" @autospa_poznan "}""").igUsername)
    }

    /**
     * „Reklamodawca nie ma Instagrama" to wynik POPRAWNY i częsty. Musi być
     * odróżnialny od niepowodzenia, bo na tym rozróżnieniu stoi cały alert:
     * gdyby oba wyglądały tak samo, przebudowa strony przez Meta byłaby
     * nieodróżnialna od prawdy o reklamodawcach.
     */
    @Test
    fun `brak profilu to nie jest awaria`() {
        val result = parse("""{"status":"empty","igUsername":null}""")

        assertEquals(IgLookupStatus.EMPTY, result.status)
        assertNull(result.igUsername)
        assertFalse(result.status.isFailure, "EMPTY nie może liczyć się jako awaria")
    }

    @Test
    fun `stany niepowodzenia sa oznaczone jako awarie`() {
        listOf("blocked", "timeout", "error").forEach { status ->
            val result = parse("""{"status":"$status","igUsername":null}""")
            assert(result.status.isFailure) { "$status powinien liczyć się jako awaria" }
        }
    }

    /**
     * Sidecar obiecuje, że przy OK jest nazwa. Gdyby kiedyś skłamał — po zmianie
     * po jego stronie albo przez błąd — wolimy zapisać EMPTY niż wiersz „udany"
     * bez wartości, który zablokowałby ponowne sprawdzenie na zawsze.
     */
    @Test
    fun `OK bez nazwy schodzi do EMPTY, zeby nie zablokowac ponowienia`() {
        val result = parse("""{"status":"ok","igUsername":null}""")

        assertEquals(IgLookupStatus.EMPTY, result.status)
        assertNull(result.igUsername)
    }

    /** Nieznany status z przyszłej wersji sidecara nie może udawać sukcesu. */
    @Test
    fun `nieznany status traktujemy jako blad`() {
        assertEquals(IgLookupStatus.ERROR, parse("""{"status":"cokolwiek"}""").status)
    }

    /**
     * Wyłączony resolver nie próbuje się łączyć. Instalacja bez sidecara ma
     * działać normalnie, tylko bez nazw profili.
     */
    @Test
    fun `wylaczony resolver nie siega do sieci`() {
        val result = client(enabled = false).resolve("101137765342120")

        assertEquals(IgLookupStatus.ERROR, result.status)
        assertNull(result.igUsername)
    }
}
