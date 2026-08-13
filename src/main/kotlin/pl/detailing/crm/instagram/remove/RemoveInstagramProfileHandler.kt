package pl.detailing.crm.instagram.remove

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramInsightRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileSuggestionRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InstagramProfileStatus

@Service
class RemoveInstagramProfileHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postSnapshotRepository: InstagramPostSnapshotRepository,
    private val metricsSnapshotRepository: InstagramProfileMetricsSnapshotRepository,
    private val statsWeeklyRepository: InstagramProfileStatsWeeklyRepository,
    private val topicRepository: InstagramPostTopicRepository,
    private val insightRepository: InstagramInsightRepository,
    private val suggestionRepository: InstagramProfileSuggestionRepository
) {

    /**
     * Usuwa subskrypcję studia. Gdy żadne inne studio nie obserwuje profilu,
     * kasuje kaskadowo wszystkie dane globalne profilu (posty, tematy, metryki,
     * agregaty, insighty) – ten sam mechanizm obsługuje żądania usunięcia danych (RODO).
     */
    @Transactional
    fun handle(command: RemoveInstagramProfileCommand) {
        val studioProfile = studioProfileRepository.findByStudioIdAndId(
            command.studioId.value,
            command.studioProfileId.value
        ) ?: throw EntityNotFoundException(
            "Profil o id=${command.studioProfileId} nie istnieje w tym studio."
        )

        val profileId = studioProfile.profileId
        studioProfileRepository.delete(studioProfile)

        val remainingSubscriptions = studioProfileRepository.countByProfileIdAndStatus(
            profileId,
            InstagramProfileStatus.ACTIVE
        ) + studioProfileRepository.countByProfileIdAndStatus(
            profileId,
            InstagramProfileStatus.PENDING_APPROVAL
        )

        if (remainingSubscriptions == 0L) {
            val postIds = postSnapshotRepository.findByProfileIdOrderByTakenAtDesc(profileId).map { it.id }
            if (postIds.isNotEmpty()) topicRepository.deleteByPostIdIn(postIds)
            postSnapshotRepository.deleteByProfileId(profileId)
            metricsSnapshotRepository.deleteByProfileId(profileId)
            statsWeeklyRepository.deleteByProfileId(profileId)
            insightRepository.deleteByProfileId(profileId)
            suggestionRepository.deleteByProfileId(profileId)
            profileRepository.deleteById(profileId)
        }
    }
}
