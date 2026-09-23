package pl.detailing.crm.rolepreview

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ConflictException
import java.util.UUID

/**
 * Bezpiecznik piaskownic podglądu roli: nic, co dzieje się w piaskownicy, nie wychodzi poza
 * nasz system - ani SMS, ani e-mail, ani powiadomienie, faktura do KSeF, płatność, zapytanie
 * do GUS, Instagrama, Meta czy OpenAI.
 *
 * Piaskownica działa w tym samym procesie co prawdziwe studia, więc ma w zasięgu prawdziwe
 * klucze do tych usług. Dlatego każda integracja pyta tu, zanim cokolwiek wyśle; zatrzymana
 * akcja trafia do panelu podglądu jako „system wysłałby…". Które klasy muszą pytać, pilnuje
 * test OutboundIntegrationSurfaceTest - nowa integracja bez bezpiecznika nie przejdzie builda.
 */
@Service
class RolePreviewOutboundGuard(
    private val studios: RolePreviewStudios,
    private val effects: RolePreviewEffects
) {

    /**
     * True, gdy akcja NIE może wyjść na zewnątrz, bo studio jest piaskownicą - wtedy jest już
     * zapisana w panelu podglądu, a wywołujący udaje sukces. Dla akcji, które dzieją się same
     * (powiadomienia po zmianie statusu, kampanie): proces ma iść dalej, jakby wysłał.
     */
    fun intercepts(studioId: UUID?, channel: SimulatedEffectChannel, recipient: String?, summary: String): Boolean {
        if (studioId == null || !studios.isRolePreview(studioId)) return false
        effects.record(studioId, channel, recipient, summary)
        return true
    }

    /**
     * Zatrzymuje akcję, na której wynik użytkownik czeka (zapytanie do GUS, wygenerowanie
     * treści przez AI): w piaskownicy nie ma czego udawać, więc mówimy wprost, co by się stało.
     */
    fun requireOutsideSandbox(studioId: UUID?, channel: SimulatedEffectChannel, summary: String) {
        if (intercepts(studioId, channel, null, summary)) {
            throw ConflictException("W prawdziwym studiu: $summary. Podgląd roli niczego nie wysyła poza system.")
        }
    }

    fun isSandbox(studioId: UUID?): Boolean = studioId != null && studios.isRolePreview(studioId)
}
