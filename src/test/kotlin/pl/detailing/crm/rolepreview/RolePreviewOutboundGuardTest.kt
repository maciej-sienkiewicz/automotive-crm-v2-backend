package pl.detailing.crm.rolepreview

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.ConflictException
import java.util.UUID

/**
 * Bezpiecznik piaskownicy: prawdziwe studio nie zauważa jego istnienia, a piaskownica nie
 * wychodzi na zewnątrz - zamiast tego zostawia w panelu podglądu ślad „system wysłałby…".
 */
class RolePreviewOutboundGuardTest {

    private val studios = mockk<RolePreviewStudios>()
    private val effects = mockk<RolePreviewEffects>(relaxed = true)
    private val guard = RolePreviewOutboundGuard(studios, effects)

    private val sandbox = UUID.randomUUID()
    private val realStudio = UUID.randomUUID()

    init {
        every { studios.isRolePreview(sandbox) } returns true
        every { studios.isRolePreview(realStudio) } returns false
    }

    @Test
    fun `prawdziwe studio wysyla normalnie i nie zostawia sladu w podgladzie`() {
        assertFalse(guard.intercepts(realStudio, SimulatedEffectChannel.SMS, "+48600100200", "Przypomnienie o wizycie"))
        assertFalse(guard.isSandbox(realStudio))

        verify(exactly = 0) { effects.record(any(), any(), any(), any()) }
    }

    @Test
    fun `wysylka bez studia nie jest wysylka piaskownicy`() {
        assertFalse(guard.intercepts(null, SimulatedEffectChannel.EMAIL, "ktos@firma.pl", "Wiadomość"))
        assertFalse(guard.isSandbox(null))

        verify(exactly = 0) { studios.isRolePreview(any()) }
    }

    @Test
    fun `piaskownica zatrzymuje wysylke i zapisuje co by sie stalo`() {
        assertTrue(guard.intercepts(sandbox, SimulatedEffectChannel.SMS, "+48000100200", "Przypomnienie o wizycie"))

        verify(exactly = 1) {
            effects.record(sandbox, SimulatedEffectChannel.SMS, "+48000100200", "Przypomnienie o wizycie")
        }
    }

    @Test
    fun `akcja na ktorej wynik ktos czeka konczy sie wprost opisem tego co by sie stalo`() {
        val error = assertThrows<ConflictException> {
            guard.requireOutsideSandbox(sandbox, SimulatedEffectChannel.AI, "treść SMS-a wygenerowałby model AI")
        }

        assertTrue(error.message!!.contains("treść SMS-a wygenerowałby model AI"), error.message)
        verify(exactly = 1) { effects.record(sandbox, SimulatedEffectChannel.AI, null, any()) }
    }

    @Test
    fun `w prawdziwym studiu akcja idzie dalej`() {
        assertDoesNotThrow {
            guard.requireOutsideSandbox(realStudio, SimulatedEffectChannel.COMPANY_REGISTRY, "zapytanie do GUS")
        }
    }
}
