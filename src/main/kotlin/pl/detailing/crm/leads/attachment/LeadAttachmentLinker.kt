package pl.detailing.crm.leads.attachment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import java.util.UUID

/**
 * Podpina do leada załączniki wiadomości, z której lead powstał.
 *
 * Klient pisze „proszę o wycenę" i dokłada zdjęcia lakieru. Wiadomość przechodziła przez
 * automatyczne rozpoznanie leadów, lead powstawał — a pliki zostawały wyłącznie
 * w skrzynce: w „Przebiegu sprawy" nie było po nich śladu, więc handlowiec musiał
 * wiedzieć, że ma ich szukać w poczcie, i trafić w odpowiedni wątek.
 *
 * Wołane ze WSZYSTKICH ścieżek, w których wiadomość staje się leadem — automat
 * klasyfikujący, robot formularza i ręczne „Oznacz jako lead" — bo dla użytkownika to
 * jedno i to samo zdarzenie i nie ma powodu, żeby raz działało, a raz nie.
 *
 * Obrazki osadzone w treści (`cid:`) pomijamy: to elementy układu wiadomości — logo
 * w stopce, ikonka podpisu — a nie coś, co klient przysłał do obejrzenia.
 */
@Service
class LeadAttachmentLinker(
    private val attachmentRepository: CommAttachmentRepository,
    private val leadAttachmentRepository: LeadAttachmentRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @return liczba podpiętych plików. Awaria nie może wywrócić tworzenia leada: lead
     *   bez listy załączników jest niepełny, lead nieutworzony — stracony.
     */
    fun link(leadId: UUID, message: CommMessageEntity): Int = try {
        val attachments = attachmentRepository.findMetaByMessageIdIn(listOf(message.id))
            .filterNot { it.isInline }

        val linked = attachments.count { meta ->
            if (leadAttachmentRepository.existsByLeadIdAndAttachmentId(leadId, meta.id)) {
                false
            } else {
                leadAttachmentRepository.save(
                    LeadAttachmentEntity(
                        studioId = message.studioId,
                        leadId = leadId,
                        messageId = message.id,
                        attachmentId = meta.id,
                        fileName = meta.fileName,
                        contentType = meta.contentType,
                        sizeBytes = meta.sizeBytes,
                        receivedAt = message.sentAt
                    )
                )
                true
            }
        }

        if (linked > 0) {
            log.info("[LEAD_ATTACHMENTS] Lead {} przejął {} załącznik(ów) z wiadomości {}", leadId, linked, message.id)
        }
        linked
    } catch (e: Exception) {
        log.error("[LEAD_ATTACHMENTS] Nie udało się podpiąć załączników wiadomości {} do leada {}: {}", message.id, leadId, e.message, e)
        0
    }
}
