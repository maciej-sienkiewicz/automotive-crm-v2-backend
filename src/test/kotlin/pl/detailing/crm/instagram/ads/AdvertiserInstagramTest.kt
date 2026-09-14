package pl.detailing.crm.instagram.ads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Nazwa profilu IG doklejana do reklamodawcy.
 *
 * Meta nie oddaje nazwy Instagrama w `ads_archive`, więc wyprowadzamy ją z domeny,
 * na którą kieruje reklama. Pomyłka kosztuje tu tyle samo co przy podpowiedziach
 * stron — podpisanie konkurenta cudzym kontem — więc każda reguła odsiewu ma tu test.
 *
 * Postacie podpisów wzięte z prawdziwej odpowiedzi biblioteki: obok „folia-samochodowa.pl”
 * stoi tam „https://blachexpertgaraze.pl/”, „sklep153253.shoparena.pl”, „fb.me”
 * i „booksy.com”.
 */
class AdvertiserInstagramTest {

    @Test
    fun `podpis reklamy sprowadzony do golego hosta`() {
        assertEquals("folia-samochodowa.pl", AdvertiserInstagram.hostOf("folia-samochodowa.pl"))
        assertEquals("blachexpertgaraze.pl", AdvertiserInstagram.hostOf("https://blachexpertgaraze.pl/"))
        assertEquals("carslab.pl", AdvertiserInstagram.hostOf("https://www.carslab.pl/?utm_source=ig"))
        assertEquals("sklep153253.shoparena.pl", AdvertiserInstagram.hostOf("sklep153253.shoparena.pl"))
        assertEquals("carslab.pl", AdvertiserInstagram.hostOf("HTTP://CarsLab.pl:8080/oferta"))
    }

    @Test
    fun `tekst bez kropki nie jest domena`() {
        assertNull(AdvertiserInstagram.hostOf("Kup teraz"))
        assertNull(AdvertiserInstagram.hostOf("   "))
        assertNull(AdvertiserInstagram.hostOf(null))
    }

    @Test
    fun `posrednicy odsiani bo nie prowadza do strony firmy`() {
        listOf("fb.me", "booksy.com", "l.facebook.com", "linktr.ee", "wa.me", "instagram.com")
            .forEach { assertTrue(AdvertiserInstagram.isIntermediary(it), "$it powinien być pośrednikiem") }

        assertFalse(AdvertiserInstagram.isIntermediary("folia-samochodowa.pl"))
        // Własna domena kończąca się jak pośrednik to wciąż własna domena.
        assertFalse(AdvertiserInstagram.isIntermediary("mojefb.pl"))
    }

    @Test
    fun `glowna domena to ta powtarzajaca sie najczesciej po odsianiu posrednikow`() {
        val captions = listOf(
            "fb.me",
            "folia-samochodowa.pl",
            "booksy.com",
            "https://folia-samochodowa.pl/pl/c/Folie",
            "sklep153253.shoparena.pl"
        )
        assertEquals("folia-samochodowa.pl", AdvertiserInstagram.primaryHost(captions))
    }

    @Test
    fun `reklamodawca kierujacy wylacznie na posrednikow nie ma domeny`() {
        assertNull(AdvertiserInstagram.primaryHost(listOf("fb.me", "booksy.com", null)))
        assertNull(AdvertiserInstagram.primaryHost(emptyList()))
    }

    @Test
    fun `nazwa profilu wyciagnieta ze stopki`() {
        val html = """
            <footer>
              <a href="https://www.facebook.com/CarArtDetailing">Facebook</a>
              <a href="https://www.instagram.com/carartdetailing/">Instagram</a>
            </footer>
        """.trimIndent()
        assertEquals("carartdetailing", AdvertiserInstagram.handleFrom(html))
    }

    @Test
    fun `odnosnik do posta nie jest nazwa profilu`() {
        val html = """<a href="https://instagram.com/p/C3xAbCdEfGh/">nasza realizacja</a>"""
        assertNull(AdvertiserInstagram.handleFrom(html))
    }

    @Test
    fun `wygrywa konto powtarzajace sie na stronie a nie pierwsze napotkane`() {
        // Pierwszy link jest cudzy (wpis na blogu), własny profil wisi w nagłówku i stopce.
        val html = """
            <article><a href="https://instagram.com/p/C3xAbCd">post</a>
              <a href="https://www.instagram.com/klient_ktory_przyslal_zdjecie">klient</a></article>
            <header><a href="https://instagram.com/carslab.krakow">IG</a></header>
            <footer><a href="https://www.instagram.com/carslab.krakow/">Obserwuj</a></footer>
        """.trimIndent()
        assertEquals("carslab.krakow", AdvertiserInstagram.handleFrom(html))
    }

    @Test
    fun `strona bez Instagrama nie zwraca nic`() {
        assertNull(AdvertiserInstagram.handleFrom("<html><body>Kontakt: 500 100 200</body></html>"))
    }

    @Test
    fun `adresy z sieci wewnetrznej sa poza zasiegiem`() {
        // Domena bierze się z reklamy, czyli od obcego — serwer nie może nią zapukać do siebie.
        listOf(
            "127.0.0.1",        // pętla zwrotna
            "10.1.2.3",         // sieć prywatna
            "192.168.0.1",      // sieć prywatna
            "172.16.4.5",       // sieć prywatna
            "169.254.169.254",  // poświadczenia chmury
            "0.0.0.0",
            "::1"
        ).forEach {
            assertTrue(
                AdvertiserInstagram.isPrivateAddress(InetAddress.getByName(it)),
                "$it powinien być poza zasięgiem"
            )
        }
    }

    @Test
    fun `zwykly adres w internecie przechodzi`() {
        listOf("8.8.8.8", "1.1.1.1").forEach {
            assertFalse(AdvertiserInstagram.isPrivateAddress(InetAddress.getByName(it)), "$it powinien przejść")
        }
    }

    @Test
    fun `link w bio porownywany po hoscie a nie po calym adresie`() {
        assertTrue(AdvertiserInstagram.sameHost("carslab.pl", "https://www.carslab.pl/?utm_source=instagram"))
        assertTrue(AdvertiserInstagram.sameHost("carslab.pl", "carslab.pl"))
        assertFalse(AdvertiserInstagram.sameHost("carslab.pl", "https://niecarslab.pl/"))
        assertFalse(AdvertiserInstagram.sameHost("carslab.pl", null))
    }
}
