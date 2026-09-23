package pl.detailing.crm.rolepreview

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.util.UUID

/**
 * „Czy to studio jest piaskownicą?" pada przy każdym żądaniu i każdej wysyłce, więc odpowiedź
 * jest trzymana w pamięci. Test pinuje dwie rzeczy, od których zależy bezpieczeństwo: zakładana
 * piaskownica jest piaskownicą, zanim jej wiersz trafi do bazy, a brak studia nigdy nie zostaje
 * zapamiętany jako „zwykłe studio".
 */
class RolePreviewStudiosTest {

    private val repository = mockk<StudioRepository>()
    private val studios = RolePreviewStudios(repository)

    @Test
    fun `rodzaj studia jest zapamietywany - drugie pytanie nie idzie do bazy`() {
        val id = UUID.randomUUID()
        every { repository.findKindById(id) } returns StudioKind.REGULAR

        assertFalse(studios.isRolePreview(id))
        assertFalse(studios.isRolePreview(id))

        verify(exactly = 1) { repository.findKindById(id) }
    }

    @Test
    fun `brak studia nie jest zapamietywany`() {
        val id = UUID.randomUUID()
        every { repository.findKindById(id) } returnsMany listOf(null, StudioKind.ROLE_PREVIEW)

        assertFalse(studios.isRolePreview(id))
        // Chwilę później wiersz jest już zatwierdzony - odpowiedź musi to zobaczyć.
        assertTrue(studios.isRolePreview(id))
    }

    @Test
    fun `zakladana piaskownica jest piaskownica zanim trafi do bazy`() {
        val id = UUID.randomUUID()

        studios.rememberRolePreview(id)

        assertTrue(studios.isRolePreview(id))
        verify(exactly = 0) { repository.findKindById(any()) }
    }

    @Test
    fun `po usunieciu piaskownicy jej identyfikator o niczym nie swiadczy`() {
        val id = UUID.randomUUID()
        every { repository.findKindById(id) } returns null
        studios.rememberRolePreview(id)

        studios.forget(id)

        assertFalse(studios.isRolePreview(id))
        verify(exactly = 1) { repository.findKindById(id) }
    }
}
