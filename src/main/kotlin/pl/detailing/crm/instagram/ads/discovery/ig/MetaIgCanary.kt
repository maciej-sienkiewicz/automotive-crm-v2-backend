package pl.detailing.crm.instagram.ads.discovery.ig

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Kanarek: codzienne sprawdzenie, czy odczyt profili IG w ogóle jeszcze działa.
 *
 * PROBLEM, KTÓRY ROZWIĄZUJE. Cały mechanizm opiera się na klikaniu po napisach
 * w interfejsie Biblioteki reklam („Zobacz szczegóły reklamy", „Informacje
 * o reklamodawcy"). Meta przebuduje tę stronę prędzej czy później i wtedy nasza
 * ścieżka przestanie pasować. Awaria nie objawi się błędem — objawi się CISZĄ:
 * każda kolejna strona zacznie wracać jako „reklamodawca nie ma Instagrama",
 * co jest wynikiem normalnym, częstym i nie do odróżnienia od prawdy.
 *
 * Zwykłe liczniki błędów są tu ślepe. Potrzebny jest punkt odniesienia:
 * strona, o której WIEMY, że profil ma. Jeśli przy niej przestajemy widzieć
 * nazwę, to nie strona się zmieniła, tylko my przestaliśmy umieć czytać.
 *
 * Kanarek celowo NIE zapisuje wyniku do tabeli wyszukiwań — sprawdza mechanizm,
 * a nie zbiera dane. Dzięki temu nie zaśmieca licznika prób ani historii.
 */
@Component
class MetaIgCanary(
    private val resolver: MetaIgResolverClient,
    private val lookupService: MetaIgLookupService,
    @Value("\${meta.ads.ig-resolver.canary-page-id:}") private val canaryPageId: String,
    @Value("\${meta.ads.ig-resolver.canary-expected:}") private val expectedHandle: String
) {
    private val log = LoggerFactory.getLogger(MetaIgCanary::class.java)

    /**
     * Raz na dobę, w nocy. Częściej nie ma sensu: strony Meta nie przebudowuje
     * co godzinę, a każde sprawdzenie to uruchomienie przeglądarki i ruch do niej.
     */
    @Scheduled(cron = "\${meta.ads.ig-resolver.canary-cron:0 20 4 * * *}")
    fun check() {
        if (!resolver.enabled) return
        if (canaryPageId.isBlank() || expectedHandle.isBlank()) {
            log.debug("Kanarek profili IG nieskonfigurowany — pomijam.")
            return
        }

        val result = resolver.resolve(canaryPageId)
        val ok = result.status == IgLookupStatus.OK &&
            result.igUsername.equals(expectedHandle, ignoreCase = true)

        lookupService.markCanary(ok)

        if (ok) {
            log.debug("Kanarek profili IG w porządku ({}).", expectedHandle)
            return
        }

        // WARN, nie ERROR: to nie jest awaria aplikacji, tylko utrata funkcji
        // pomocniczej. Alert i tak pójdzie z Prometheusa, a ERROR w logach
        // produkcyjnych ma znaczyć „coś jest zepsute u nas".
        log.warn(
            "Kanarek profili IG NIE przeszedł: dla strony {} oczekiwano „{}”, " +
                "dostaliśmy status {} i nazwę „{}” ({}). " +
                "Najpewniej Meta przebudowała Bibliotekę reklam i ścieżka kliknięć " +
                "przestała pasować — zebrane dotąd nazwy zostają nietknięte, " +
                "ale nowych nie ustalimy do czasu poprawki.",
            canaryPageId, expectedHandle, result.status, result.igUsername, result.reason
        )
    }
}
