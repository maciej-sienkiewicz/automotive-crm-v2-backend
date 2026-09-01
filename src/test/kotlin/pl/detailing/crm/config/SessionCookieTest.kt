package pl.detailing.crm.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession
import org.springframework.session.web.http.CookieSerializer

/**
 * Kontrakt cookie sesji. Testujemy go, bo domyślne zachowanie Spring Session jest tu
 * odwrotne od tego, czego oczekuje użytkownik: bez jawnego Max-Age przeglądarka
 * kasuje cookie przy zamknięciu okna i wylogowuje kogoś, kto ma na serwerze żywą
 * sesję. Usunięcie beana cookieSerializer cofnęłoby to po cichu — ten test krzyczy.
 */
class SessionCookieTest {

    private fun writtenCookie(appEnv: String) = MockHttpServletResponse().also { response ->
        val serializer: CookieSerializer = SecurityConfig(appEnv).cookieSerializer()
        serializer.writeCookieValue(
            CookieSerializer.CookieValue(MockHttpServletRequest(), response, "session-id")
        )
    }.getCookie("SESSION")
        // MockHttpServletResponse.getCookie jest @Nullable; brak cookie to złamany kontrakt.
        ?: error("Serializer nie wystawił cookie SESSION")

    @Test
    fun `cookie sesji przezywa zamkniecie przegladarki`() {
        val cookie = writtenCookie("production")
        checkNotNull(cookie) { "Serializer nie wystawił cookie SESSION" }
        // Sedno poprawki: Max-Age dodatnie = cookie trwałe, zapisane na dysku.
        // Wartość -1 (domyślna w Spring Session) to cookie sesyjne, kasowane wraz z oknem.
        assertTrue(cookie.maxAge > 0, "Cookie sesji musi być trwałe, a nie sesyjne")
    }

    @Test
    fun `cookie zyje dokladnie tyle co sesja po stronie serwera`() {
        // Rozjazd w którąkolwiek stronę boli: dłuższe cookie prowadzi do nieistniejącej
        // sesji, krótsze wylogowuje mimo sesji żywej. Wartość czytamy z adnotacji, żeby
        // zmiana TTL sesji bez zmiany cookie została tu złapana.
        val sessionTtl = SecurityConfig::class.java
            .getAnnotation(EnableRedisHttpSession::class.java)
            .maxInactiveIntervalInSeconds
        assertEquals(sessionTtl, writtenCookie("production").maxAge)
    }

    @Test
    fun `poza srodowiskiem lokalnym cookie jest Secure i HttpOnly`() {
        val cookie = writtenCookie("production")
        assertTrue(cookie.secure, "Na produkcji cookie sesji musi być Secure")
        assertTrue(cookie.isHttpOnly, "Cookie sesji nie może być czytelne dla skryptów")
    }

    @Test
    fun `lokalnie cookie nie jest Secure, bo dev pracuje po HTTP`() {
        // Z flagą Secure przeglądarka odrzuciłaby cookie na http://localhost
        // i logowanie na maszynie dewelopera przestałoby działać w ogóle.
        assertFalse(writtenCookie("local").secure)
    }
}
