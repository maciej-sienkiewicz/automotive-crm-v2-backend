package pl.detailing.crm.instagram.ads.discovery

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Wykluczeni reklamodawcy — dwa poziomy, jedna reguła.
 *
 * **Globalne** (`studioId = null`) zakłada administrator aplikacji wprost w bazie.
 * To boty, hurtownie i profile zza granicy, które trafiają w polskie frazy, ale nie
 * są niczyją konkurencją — nikomu nie pomagają, a psują każdą tabelę. Studia ich nie
 * widzą i nie mogą cofnąć.
 *
 * **Studia** (`studioId` wypełnione) zakłada samo studio z poziomu tabeli. Firma bywa
 * legalnym reklamodawcą, a mimo to nie jest konkurencją tego konkretnego studia:
 * dostawca chemii, sieć myjni, warsztat z sąsiedniej branży.
 *
 * Filtrowanie dzieje się na ODCZYCIE, nie na pobraniu. Reklamy w cache są wspólne
 * dla wszystkich najemców, więc wykluczenie jednego studia nie może ich usuwać —
 * usunęłoby je wszystkim. Wspólny cache zostaje kompletny, tabela bywa krótsza.
 */
@Service
class AdvertiserBlockService(
    private val blockRepository: AdvertiserBlockRepository,
    @Value("\${meta.ads.discovery.max-blocks-per-studio:200}") private val maxBlocksPerStudio: Int
) {
    private val log = LoggerFactory.getLogger(AdvertiserBlockService::class.java)

    /** Numery stron, których to studio nie ma zobaczyć: globalne plus własne. */
    @Transactional(readOnly = true)
    fun blockedPageIds(studioId: StudioId): Set<String> =
        blockRepository.findEffectiveFor(studioId.value).map { it.pageId }.toSet()

    /** Czarna lista samego studia — tylko ona daje się cofnąć z poziomu ekranu. */
    @Transactional(readOnly = true)
    fun listOwn(studioId: StudioId): List<BlockedAdvertiserDto> =
        blockRepository.findByStudioIdOrderByCreatedAtDesc(studioId.value).map { it.toDto() }

    /**
     * Ukrycie reklamodawcy w tabeli tego studia. Powtórzone dla tego samego numeru
     * odświeża nazwę i powód zamiast wywracać się na unikalności — przycisk „ukryj"
     * kliknięty dwa razy ma dać ten sam skutek co raz.
     */
    @Transactional
    fun block(
        studioId: StudioId,
        userId: UserId,
        request: BlockAdvertiserRequest
    ): BlockedAdvertiserDto {
        val pageId = request.pageId.trim().takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: throw ValidationException("Nieprawidłowy identyfikator strony reklamodawcy.")

        blockRepository.findByStudioIdAndPageId(studioId.value, pageId)?.let { existing ->
            existing.pageName = request.pageName?.trim()?.take(200) ?: existing.pageName
            existing.reason = request.reason?.trim()?.take(300) ?: existing.reason
            return blockRepository.save(existing).toDto()
        }

        if (blockRepository.countByStudioId(studioId.value) >= maxBlocksPerStudio) {
            throw ConflictException(
                "Osiągnięto limit $maxBlocksPerStudio ukrytych reklamodawców. Przywróć część, aby ukryć kolejnych."
            )
        }

        val saved = blockRepository.save(
            AdvertiserBlockEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                pageId = pageId,
                pageName = request.pageName?.trim()?.take(200),
                reason = request.reason?.trim()?.take(300),
                createdByUserId = userId.value,
                createdAt = Instant.now()
            )
        )
        log.info("Odkrywanie reklam: studio {} ukryło reklamodawcę {}", studioId.value, pageId)
        return saved.toDto()
    }

    /**
     * Przywrócenie reklamodawcy. Dotyczy wyłącznie wykluczeń tego studia — wiersz
     * globalny ma `studio_id IS NULL`, więc nie trafi w warunek i zostanie nietknięty
     * niezależnie od tego, co przyjdzie w żądaniu.
     */
    @Transactional
    fun unblock(studioId: StudioId, pageId: String): Boolean =
        blockRepository.deleteByStudioIdAndPageId(studioId.value, pageId.trim()) > 0

    private fun AdvertiserBlockEntity.toDto() = BlockedAdvertiserDto(
        pageId = pageId,
        pageName = pageName,
        reason = reason,
        createdAt = createdAt.toString()
    )
}
