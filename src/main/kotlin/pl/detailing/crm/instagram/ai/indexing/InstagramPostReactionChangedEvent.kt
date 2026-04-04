package pl.detailing.crm.instagram.ai.indexing

import pl.detailing.crm.shared.InstagramPostReaction
import pl.detailing.crm.shared.InstagramPostSnapshotId
import pl.detailing.crm.shared.StudioId

/**
 * Zdarzenie domenowe publikowane przez [pl.detailing.crm.instagram.reaction.ReactToInstagramPostHandler]
 * po każdej zmianie reakcji studia na post Instagramowy.
 *
 * Konsumowany asynchronicznie przez [InstagramPostIndexingService],
 * który klasyfikuje post i aktualizuje bazę wektorową.
 *
 * @param studioId  Identyfikator studia, które zareagowało
 * @param postId    Identyfikator snapshotu posta
 * @param reaction  Nowa reakcja (LIKED/DISLIKED) lub null gdy reakcja została usunięta
 */
data class InstagramPostReactionChangedEvent(
    val studioId: StudioId,
    val postId: InstagramPostSnapshotId,
    val reaction: InstagramPostReaction?
)
