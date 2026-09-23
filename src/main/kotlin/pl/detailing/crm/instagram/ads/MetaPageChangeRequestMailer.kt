package pl.detailing.crm.instagram.ads

import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Prośba do administratora o zmianę albo odpięcie strony na Facebooku.
 *
 * ## Dlaczego prośba, a nie zapis
 *
 * Powiązanie profil → strona siedzi na GLOBALNYM wierszu profilu (`instagram_profiles`),
 * wspólnym dla wszystkich studiów, które ten profil obserwują. Zapis wprost z aplikacji
 * znaczył więc, że jedno studio przestawia dane pozostałym, a przy okazji kasuje wspólne
 * migawki reklam. Pierwsze wskazanie strony zostaje natychmiastowe — nie ma czego zepsuć,
 * bo profil nie miał dotąd żadnej strony. Każda ZMIANA i każde ODPIĘCIE idzie mailem do
 * administratora, który sprawdza numer i wprowadza go sam.
 *
 * Adres jest ten sam, co przy „Zgłoś problem" (`support.report.recipient-email`) — świadomie
 * jedna skrzynka na wszystko, co trafia z aplikacji do człowieka po naszej stronie.
 *
 * Mail niesie też liczbę studiów obserwujących profil, bo to ona mówi administratorowi,
 * ilu klientów dotknie zmiana.
 */
@Service
class MetaPageChangeRequestMailer(
    private val emailProvider: EmailProvider,
    private val studioRepository: StudioRepository,
    private val studioProfileRepository: StudioInstagramProfileRepository,
    @Value("\${support.report.recipient-email}") private val recipientEmail: String,
    private val rolePreviewGuard: RolePreviewOutboundGuard
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.of("Europe/Warsaw"))

        /**
         * Zapora przed zalaniem skrzynki, nie granica bezpieczeństwa: trzymana w pamięci,
         * więc restart ją zeruje. Zgłoszenie tej samej zmiany co chwilę nie niesie nowej
         * informacji, a administrator ma decydować, nie odkopywać się z powtórek.
         */
        val THROTTLE: Duration = Duration.ofMinutes(10)
    }

    private val lastRequestAt = ConcurrentHashMap<String, Instant>()

    /**
     * @param newPageId numer żądanej strony albo null, gdy studio prosi o odpięcie
     * @return false, gdy zgłoszenie zostało wyciszone jako powtórka
     */
    fun request(
        requestedBy: UserPrincipal,
        profile: InstagramProfileEntity,
        newPageId: String?
    ): Boolean {
        // Zgłoszenie z okna podglądu roli nie trafia do administratora platformy.
        if (rolePreviewGuard.intercepts(
                requestedBy.studioId.value, SimulatedEffectChannel.EMAIL, recipientEmail,
                "Prośba o zmianę strony Facebooka dla @${profile.username}"
            )
        ) return true

        val key = "${requestedBy.studioId.value}:${profile.id}:${newPageId ?: "-"}"
        val now = Instant.now()
        val previous = lastRequestAt.put(key, now)
        if (previous != null && Duration.between(previous, now) < THROTTLE) {
            log.info(
                "Meta Ad Library: powtórzone zgłoszenie zmiany strony profilu {} ze studia {} — wyciszone",
                profile.id, requestedBy.studioId.value
            )
            return false
        }

        val studioName = studioRepository.findByStudioId(requestedBy.studioId.value)?.name ?: "(nieznane studio)"
        val watchers = studioProfileRepository.countByProfileId(profile.id)
        val action = if (newPageId == null) "ODPIĘCIE strony" else "ZMIANA strony"

        val body = buildString {
            appendLine("Prośba o $action w monitoringu reklam")
            appendLine("=================================================")
            appendLine()
            appendLine("Data zgłoszenia: ${TIMESTAMP.format(now)}")
            appendLine("Studio: $studioName (id: ${requestedBy.studioId.value})")
            appendLine("Zgłaszający: ${requestedBy.fullName} (${requestedBy.email})")
            appendLine()
            appendLine("Profil na Instagramie: @${profile.username} (id: ${profile.id})")
            appendLine("Obecna strona na Facebooku: ${profile.facebookPageId ?: "brak"}")
            appendLine("Obecna nazwa strony: ${profile.facebookPageName ?: "nieznana"}")
            appendLine("Żądana strona: ${newPageId ?: "brak (odpięcie)"}")
            appendLine()
            appendLine("Profil obserwuje studiów: $watchers")
            appendLine(
                "Zmiana dotknie wszystkich obserwujących: powiązanie i pobrane reklamy " +
                    "są wspólne dla całego CRM-u."
            )
            appendLine()
            appendLine("Nic nie zostało zapisane — decyzja i wprowadzenie zmiany należą do administratora.")
        }

        val result = emailProvider.send(
            to = recipientEmail,
            subject = "[Reklamy konkurencji] $action dla @${profile.username} — $studioName",
            bodyText = body
        )

        if (result.success) {
            log.info(
                "Meta Ad Library: zgłoszenie zmiany strony profilu {} (@{}) ze studia {} wysłane do administratora",
                profile.id, profile.username, requestedBy.studioId.value
            )
        } else {
            log.warn(
                "Meta Ad Library: nie udało się wysłać zgłoszenia zmiany strony profilu {} — {}",
                profile.id, result.errorMessage
            )
        }
        return true
    }
}
