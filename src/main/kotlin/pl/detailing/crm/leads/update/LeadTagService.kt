package pl.detailing.crm.leads.update

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadTagEntity
import pl.detailing.crm.leads.infrastructure.LeadTagRepository
import java.util.UUID

/**
 * Tagi leada. Zapis jest zawsze podmianą całego zestawu, nie doklejaniem: użytkownik
 * widzi w oknie listę zaznaczeń i oczekuje, że zapisze się dokładnie to, co widzi.
 *
 * Operujemy na kodach, nie na definicjach: kod jest tym, co leży w bazie od pierwszego
 * dnia, i przeżywa usunięcie tagu ze słownika. Etykiety dokłada dopiero warstwa DTO,
 * pytając katalog studia — dzięki temu skasowany tag nie znika z historii leada.
 */
@Service
class LeadTagService(
    private val tagRepository: LeadTagRepository
) {

    @Transactional
    fun replaceTags(leadId: UUID, tagCodes: List<String>) {
        tagRepository.deleteByLeadId(leadId)
        if (tagCodes.isEmpty()) return
        tagRepository.saveAll(
            tagCodes.distinct().map { LeadTagEntity(leadId = leadId, tagCode = it) }
        )
    }

    fun tagsOf(leadId: UUID): List<String> =
        tagRepository.findByLeadId(leadId).map { it.tagCode }

    /** Tagi wielu leadów naraz — lista leadów pobiera je jednym zapytaniem, nie N. */
    fun tagsOf(leadIds: Collection<UUID>): Map<UUID, List<String>> {
        if (leadIds.isEmpty()) return emptyMap()
        return tagRepository.findByLeadIdIn(leadIds)
            .groupBy { it.leadId }
            .mapValues { (_, rows) -> rows.map { it.tagCode } }
    }
}
