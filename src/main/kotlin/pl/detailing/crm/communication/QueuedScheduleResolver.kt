package pl.detailing.crm.communication

import org.springframework.stereotype.Component
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.queue.OutboundMessageJpaRepository
import pl.detailing.crm.shared.CommunicationStatus
import java.time.Instant
import java.util.UUID

/**
 * „W kolejce, wyjdzie o …" — termin dla wpisów QUEUED w dzienniku komunikacji.
 *
 * Dziennik nie przechowuje terminu (to własność kolejki, która może go przesunąć przy
 * ponowieniu), więc czytamy go z wiersza kolejki po `queued_message_id`, jednym
 * zapytaniem dla całej listy. Wpis, którego wiersz kolejki już nie istnieje, dostaje
 * null — UI pokaże „w kolejce" bez godziny, zamiast wymyślać ją z `sentAt`.
 */
@Component
class QueuedScheduleResolver(private val queueRepository: OutboundMessageJpaRepository) {

    /** Mapa: id wpisu dziennika → termin wysyłki, tylko dla wpisów QUEUED. */
    fun resolve(entries: List<CommunicationLogEntity>): Map<UUID, Instant> {
        val queued = entries.filter { it.status == CommunicationStatus.QUEUED && it.queuedMessageId != null }
        if (queued.isEmpty()) return emptyMap()
        val scheduledByQueueId = queueRepository
            .findAllById(queued.map { it.queuedMessageId!! }.distinct())
            .associate { it.id to it.scheduledFor }
        return queued.mapNotNull { entry -> scheduledByQueueId[entry.queuedMessageId]?.let { entry.id to it } }.toMap()
    }
}
