package pl.detailing.crm.rolepreview

import org.springframework.stereotype.Service
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Odpowiada na pytanie „czy to studio jest piaskownicą podglądu roli?" - zadawane przy każdym
 * żądaniu, każdym logowaniu i każdej próbie wysyłki na zewnątrz.
 *
 * Rodzaj studia nigdy się nie zmienia (kolumna poza UPDATE-ami), więc raz odczytana
 * odpowiedź jest prawdziwa na zawsze i może zostać w pamięci. Wyjątek to studio, którego
 * (jeszcze) nie ma: brak odpowiedzi NIE trafia do pamięci, bo piaskownica w trakcie
 * zakładania stanie się widoczna chwilę później.
 */
@Service
class RolePreviewStudios(
    private val studioRepository: StudioRepository
) {
    private val known = ConcurrentHashMap<UUID, StudioKind>()

    /** Rodzaj studia; null, gdy studia nie ma (usunięte albo jeszcze niezatwierdzone). */
    fun kindOf(studioId: UUID): StudioKind? {
        known[studioId]?.let { return it }
        val kind = studioRepository.findKindById(studioId) ?: return null
        // Odpowiedzi są niezmienne, więc przepełnienie pamięci rozwiązuje zwykłe wyczyszczenie.
        if (known.size >= MAX_REMEMBERED) known.clear()
        known[studioId] = kind
        return kind
    }

    fun isRolePreview(studioId: UUID): Boolean = kindOf(studioId) == StudioKind.ROLE_PREVIEW

    /**
     * Zakładana piaskownica jest piaskownicą od pierwszej chwili - zanim jej wiersz w ogóle
     * trafi do bazy. Wszystko, co wydarzy się w trakcie zakładania, już podlega blokadom.
     */
    fun rememberRolePreview(studioId: UUID) {
        known[studioId] = StudioKind.ROLE_PREVIEW
    }

    /** Po usunięciu piaskownicy - jej identyfikator nie będzie już o niczym świadczył. */
    fun forget(studioId: UUID) {
        known.remove(studioId)
    }

    private companion object {
        const val MAX_REMEMBERED = 50_000
    }
}
