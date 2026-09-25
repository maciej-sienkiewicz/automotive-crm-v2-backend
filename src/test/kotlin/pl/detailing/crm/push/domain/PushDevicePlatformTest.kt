package pl.detailing.crm.push.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Platforma urządzenia to podpowiedź dla kreatora powiadomień: „masz już CRM na
 * ekranie początkowym iPhone'a". Pomyłka w stronę IOS kazałaby komuś otwierać ikonę,
 * której nie ma - dlatego wszystko, co nie mówi wprost „iPhone/iPad", IOS nie jest.
 */
class PushDevicePlatformTest {

    @Test
    fun `aplikacja z ekranu poczatkowego iPhonea to IOS`() {
        assertEquals(
            PushDevicePlatform.IOS,
            PushDevicePlatform.fromUserAgent(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
            )
        )
    }

    @Test
    fun `Android to Android, choc jego User-Agent zawiera Linux`() {
        assertEquals(
            PushDevicePlatform.ANDROID,
            PushDevicePlatform.fromUserAgent(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
        )
    }

    @Test
    fun `Mac - takze iPad w trybie komputera - nie jest brany za iPhonea`() {
        assertEquals(
            PushDevicePlatform.DESKTOP,
            PushDevicePlatform.fromUserAgent(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
            )
        )
    }

    @Test
    fun `brak User-Agenta to brak podpowiedzi`() {
        assertEquals(PushDevicePlatform.UNKNOWN, PushDevicePlatform.fromUserAgent(null))
        assertEquals(PushDevicePlatform.UNKNOWN, PushDevicePlatform.fromUserAgent("  "))
    }
}
