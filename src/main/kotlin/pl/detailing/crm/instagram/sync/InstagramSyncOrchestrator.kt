package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.analytics.InsightEngine
import pl.detailing.crm.instagram.analytics.InstagramAggregationService
import pl.detailing.crm.instagram.analytics.ReportService
import pl.detailing.crm.instagram.analytics.SuggestionService
import pl.detailing.crm.instagram.analytics.TopicClassificationService
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository

/**
 * Spina pełny przepływ synchronizacji: pobranie danych → klasyfikacja tematów →
 * agregaty tygodniowe → insighty. Trzy tryby:
 *
 * - [initialSync]  – po zatwierdzeniu profilu: szczegóły + pełna historia postów (12 mies.).
 * - [dailySyncAll] – codziennie: szczegóły (snapshot obserwujących) + 1 strona postów
 *                    (nowe posty i promocje wykrywane w ≤24h, koszt 2 wywołania/profil).
 * - [weeklySyncAll] – niedziela: głęboki sync (odświeżenie liczników postów do 90 dni wstecz)
 *                    + pełny przebieg detektorów insightów.
 */
@Service
class InstagramSyncOrchestrator(
    private val profileRepository: InstagramProfileRepository,
    private val detailsSyncService: InstagramProfileDetailsSyncService,
    private val postsSyncService: InstagramSyncService,
    private val topicService: TopicClassificationService,
    private val aggregationService: InstagramAggregationService,
    private val insightEngine: InsightEngine,
    private val suggestionService: SuggestionService,
    private val reportService: ReportService
) {
    private val log = LoggerFactory.getLogger(InstagramSyncOrchestrator::class.java)

    fun initialSync(profile: InstagramProfileEntity) {
        log.info("Instagram sync: pierwsze pobranie danych dla @{}", profile.username)

        val detailsOk = runCatching { detailsSyncService.syncProfile(profile) }
            .onFailure { log.error("Instagram sync: błąd szczegółów @{}: {}", profile.username, it.message, it) }
            .getOrDefault(false)

        if (detailsOk && profile.isPrivate) {
            log.warn("Instagram sync: @{} jest kontem prywatnym – posty niedostępne", profile.username)
            return
        }

        runCatching {
            postsSyncService.syncProfilePosts(profile, InstagramSyncService.SyncDepth.DEEP)
            postProcess(profile)
        }.onFailure { log.error("Instagram sync: błąd postów @{}: {}", profile.username, it.message, it) }

        // Sugestie podobnych profili – 1 wywołanie API, cache 30 dni
        suggestionService.refreshForProfile(profile)

        // Świeżo zatwierdzony profil od razu zasila wnioski (krótszy time-to-value)
        runCatching { insightEngine.runDailyForAllStudios() }
            .onFailure { log.error("Instagram sync: błąd insightów po initial sync: {}", it.message, it) }
    }

    fun dailySyncAll() {
        val profiles = profileRepository.findAllActiveDistinct()
        if (profiles.isEmpty()) return

        log.info("Instagram daily sync: start dla {} profili", profiles.size)
        var errors = 0

        profiles.forEach { profile ->
            try {
                detailsSyncService.syncProfile(profile)
                if (!profile.isPrivate) {
                    postsSyncService.syncProfilePosts(profile, InstagramSyncService.SyncDepth.LIGHT)
                }
                topicService.classifyNewPosts(profile.id)
                aggregationService.recomputeProfile(profile.id, InstagramAggregationService.RECENT_WINDOW_WEEKS)
            } catch (e: Exception) {
                errors++
                log.error("Instagram daily sync: błąd dla @{}: {}", profile.username, e.message, e)
            }
        }

        insightEngine.runDailyForAllStudios()
        log.info("Instagram daily sync: zakończono ({} błędów)", errors)
    }

    fun weeklySyncAll() {
        val profiles = profileRepository.findAllActiveDistinct()
        if (profiles.isEmpty()) return

        log.info("Instagram weekly sync: start dla {} profili", profiles.size)
        var errors = 0

        profiles.forEach { profile ->
            try {
                if (!profile.isPrivate) {
                    postsSyncService.syncProfilePosts(profile, InstagramSyncService.SyncDepth.DEEP)
                }
                postProcess(profile)
                suggestionService.refreshForProfile(profile) // no-op gdy cache świeższy niż 30 dni
            } catch (e: Exception) {
                errors++
                log.error("Instagram weekly sync: błąd dla @{}: {}", profile.username, e.message, e)
            }
        }

        insightEngine.runWeeklyForAllStudios()
        runCatching { reportService.generateForAllStudios() }
            .onFailure { log.error("Instagram weekly sync: błąd generowania raportów: {}", it.message, it) }
        log.info("Instagram weekly sync: zakończono ({} błędów)", errors)
    }

    /**
     * Ręczne ponowienie dla jednego profilu oznaczonego jako niedostępny.
     * Zakres jak przy syncu dziennym (szczegóły + jedna strona postów), żeby koszt
     * jednego kliknięcia użytkownika był taki sam jak koszt zwykłego cyklu.
     *
     * @return true, gdy profil wrócił do normy (flaga błędu zdjęta).
     */
    fun resyncProfile(profile: InstagramProfileEntity): Boolean {
        val detailsOk = detailsSyncService.syncProfile(profile)

        // Konto prywatne nie udostępnia postów, więc o powodzeniu decyduje sam odczyt
        // profilu — inaczej raz ustawiona flaga zostałaby na nim na zawsze.
        if (profile.isPrivate) {
            if (detailsOk && profile.apiError) {
                profile.apiError = false
                profileRepository.save(profile)
            }
            return detailsOk
        }

        postsSyncService.syncProfilePosts(profile, InstagramSyncService.SyncDepth.LIGHT)
        postProcess(profile)
        return !profile.apiError
    }

    private fun postProcess(profile: InstagramProfileEntity) {
        topicService.classifyNewPosts(profile.id)
        aggregationService.recomputeProfile(profile.id)
    }
}
