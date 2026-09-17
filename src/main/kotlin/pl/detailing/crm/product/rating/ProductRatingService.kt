package pl.detailing.crm.product.rating

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.product.ProductRatingDto
import pl.detailing.crm.product.infrastructure.ProductRatingEntity
import pl.detailing.crm.product.infrastructure.ProductRatingRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Ocena produktu — dokładnie jedna na produkt na studio (upsert). Należy do studia,
 * nie do osoby; zapisujemy tylko, kto ostatnio ją ustawił, żeby nadpisanie nie było
 * anonimowe.
 */
@Service
class ProductRatingService(
    private val ratingRepository: ProductRatingRepository
) {
    @Transactional
    fun set(
        studioId: StudioId,
        userId: UserId,
        userName: String,
        productId: UUID,
        rating: Int,
        justification: String?
    ): ProductRatingDto {
        if (rating !in 1..5) throw ValidationException("Ocena musi mieścić się w 1..5.")
        val existing = ratingRepository.findByStudioIdAndProductId(studioId.value, productId)
        val now = Instant.now()
        val entity = existing ?: ProductRatingEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            productId = productId,
            rating = rating,
            justification = null,
            ratedBy = userId.value,
            ratedByName = userName,
            ratedAt = now
        )
        entity.rating = rating
        entity.justification = justification?.trim()?.ifBlank { null }
        entity.ratedBy = userId.value
        entity.ratedByName = userName
        entity.ratedAt = now
        val saved = ratingRepository.save(entity)
        return ProductRatingDto(saved.rating, saved.justification, saved.ratedByName, saved.ratedAt)
    }

    @Transactional
    fun clear(studioId: StudioId, productId: UUID) {
        ratingRepository.findByStudioIdAndProductId(studioId.value, productId)?.let {
            ratingRepository.delete(it)
        }
    }
}
