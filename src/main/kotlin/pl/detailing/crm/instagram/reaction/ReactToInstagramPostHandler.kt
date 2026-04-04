package pl.detailing.crm.instagram.reaction

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramPostReactionEntity
import pl.detailing.crm.instagram.infrastructure.StudioInstagramPostReactionRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.*

data class ReactToInstagramPostCommand(
    val studioId: StudioId,
    val postId: InstagramPostSnapshotId,
    val reaction: InstagramPostReaction?
)

@Service
class ReactToInstagramPostHandler(
    private val postSnapshotRepository: InstagramPostSnapshotRepository,
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val reactionRepository: StudioInstagramPostReactionRepository
) {

    @Transactional
    fun handle(command: ReactToInstagramPostCommand): InstagramPostReaction? {
        val post = postSnapshotRepository.findById(command.postId.value).orElse(null)
            ?: throw EntityNotFoundException("Post o id=${command.postId} nie istnieje.")

        val hasAccess = studioProfileRepository.existsByStudioIdAndProfileIdAndStatus(
            command.studioId.value,
            post.profileId,
            InstagramProfileStatus.ACTIVE
        )
        if (!hasAccess) {
            throw ForbiddenException("Studio nie ma dostępu do tego posta.")
        }

        val existing = reactionRepository.findByStudioIdAndPostId(command.studioId.value, command.postId.value)

        if (command.reaction == null) {
            if (existing != null) {
                reactionRepository.delete(existing)
            }
            return null
        }

        if (existing != null) {
            existing.reaction = command.reaction
            existing.updatedAt = Instant.now()
            reactionRepository.save(existing)
        } else {
            reactionRepository.save(
                StudioInstagramPostReactionEntity(
                    id = UUID.randomUUID(),
                    studioId = command.studioId.value,
                    postId = command.postId.value,
                    reaction = command.reaction,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )
        }

        return command.reaction
    }
}
