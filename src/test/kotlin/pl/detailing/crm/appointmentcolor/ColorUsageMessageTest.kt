package pl.detailing.crm.appointmentcolor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointmentcolor.delete.wizytOrRezerwacji

/**
 * Komunikat o odmowie usunięcia koloru czyta pracownik studia, więc liczebnik
 * musi się zgadzać z rzeczownikiem — „używany przez 2 wizyt" wygląda jak błąd
 * aplikacji, a nie jak wyjaśnienie.
 */
class ColorUsageMessageTest {

    @Test
    fun `pojedyncza wizyta`() {
        assertEquals("wizytę lub rezerwację", wizytOrRezerwacji(1))
    }

    @Test
    fun `od dwoch do czterech`() {
        assertEquals("wizyty lub rezerwacje", wizytOrRezerwacji(2))
        assertEquals("wizyty lub rezerwacje", wizytOrRezerwacji(4))
        assertEquals("wizyty lub rezerwacje", wizytOrRezerwacji(23))
    }

    @Test
    fun `piec i wiecej`() {
        assertEquals("wizyt lub rezerwacji", wizytOrRezerwacji(5))
        assertEquals("wizyt lub rezerwacji", wizytOrRezerwacji(21))
        assertEquals("wizyt lub rezerwacji", wizytOrRezerwacji(100))
    }

    @Test
    fun `nastki maja wlasna forme`() {
        // 12-14 wygląda jak 2-4, ale odmienia się jak 5+.
        assertEquals("wizyt lub rezerwacji", wizytOrRezerwacji(12))
        assertEquals("wizyt lub rezerwacji", wizytOrRezerwacji(14))
        assertEquals("wizyt lub rezerwacji", wizytOrRezerwacji(113))
    }
}
