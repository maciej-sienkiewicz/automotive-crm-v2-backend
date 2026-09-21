package pl.detailing.crm.leads.conversation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.formmail.FormMailExtractionRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import java.time.Instant
import java.util.UUID

/**
 * Rozmowa leada, który NIE MA wątku — czyli zgłoszenia z formularza na stronie.
 *
 * ── Skąd bierze się ten problem ──────────────────────────────────────────────
 *
 * Robot formularza wysyła wszystkie zgłoszenia z jednego adresu i na jeden adres,
 * więc serwer pocztowy skleja je w JEDEN wątek — u jednego studia urósł do 249
 * wiadomości od kilkudziesięciu różnych osób. Dlatego lead z formularza celowo nie
 * dostaje `threadId`: podpięcie tego wątku pokazałoby Zbyszkowi korespondencję
 * Grzegorza, Patrycji i trzydziestu innych klientów.
 *
 * Cena za to była jednak wyższa, niż się wydawało. Wszystko, co w CRM-ie wie
 * cokolwiek o rozmowie, pyta o nią PRZEZ WĄTEK — więc lead z formularza nie miał
 * ani jednej wiadomości na osi czasu (zgłoszenie klienta nie było widoczne nigdzie),
 * a po wysłaniu odpowiedzi dalej liczył się jako nieodpisany: `lastOutboundAt`
 * zostawało puste, więc sprawa wisiała w „Czeka na Ciebie" mimo udzielonej odpowiedzi.
 *
 * ── Reguła ───────────────────────────────────────────────────────────────────
 *
 * Rozmową leada są: zgłoszenie, które go utworzyło, oraz te wiadomości z tego samego
 * wątku, których DRUGĄ STRONĄ jest ten konkretny klient — przychodzące po nadawcy,
 * wychodzące po odbiorcy. Klienci odpisują w ten sam wątek (widać to w skrzynce:
 * odpowiedzi trafiają obok zgłoszeń innych osób), więc ten warunek wyciąga dokładnie
 * ich korespondencję i nic poza nią.
 *
 * Granica czasowa `sentAt >= zgłoszenie` chroni przed sklejeniem dwóch spraw tej samej
 * osoby: klient, który pisał w czerwcu i wrócił we wrześniu, ma dwa leady i starsza
 * korespondencja należy do starszego z nich.
 *
 * Reguła działa tylko dla kontaktu będącego ADRESEM E-MAIL. Zgłoszenie z samym
 * telefonem nie ma czego dopasować i zostaje przy samym zgłoszeniu — świadomie,
 * bo dopasowywanie po numerze w treści maila zgadywałoby.
 */
@Service
class FormLeadConversation(
    private val extractionRepository: FormMailExtractionRepository,
    private val messageRepository: CommMessageRepository
) {

    /** Zgłoszenie źródłowe plus korespondencja z tym klientem, chronologicznie. */
    @Transactional(readOnly = true)
    fun messagesOf(lead: LeadEntity): List<CommMessageEntity> {
        if (lead.threadId != null) return emptyList()
        val origin = originMessage(lead) ?: return emptyList()
        val contact = emailContact(lead) ?: return listOf(origin)

        val rest = messageRepository.findCounterpartyMessages(
            studioId = lead.studioId,
            threadId = origin.threadId,
            contact = contact,
            since = origin.sentAt
        )
        // Zgłoszenie przychodzi OD ROBOTA, nie od klienta, więc nie łapie się na
        // warunek „drugą stroną jest ten klient" i trzeba je dołożyć osobno.
        return (listOf(origin) + rest).distinctBy { it.id }.sortedBy { it.sentAt }
    }

    /**
     * Ostatnia wiadomość w każdą stronę, dla CAŁEJ strony listy leadów.
     *
     * Jedno zapytanie na stronę, nie jedno na leada: wołający renderuje po 25 spraw
     * naraz, a wątek robota bywa ogromny. Rzut jest lekki (kierunek, strony, czas),
     * więc filtrowanie po kliencie robimy w pamięci.
     */
    @Transactional(readOnly = true)
    fun statesOf(studioId: UUID, leads: Collection<LeadEntity>): Map<UUID, LeadConversationState> {
        val candidates = leads.filter { it.threadId == null }
        if (candidates.isEmpty()) return emptyMap()

        val origins = originMessages(candidates)
        if (origins.isEmpty()) return emptyMap()

        val rows = messageRepository.findCounterpartyRows(
            studioId,
            origins.values.map { it.threadId }.distinct()
        )

        return candidates.mapNotNull { lead ->
            val origin = origins[lead.id] ?: return@mapNotNull null
            val contact = emailContact(lead)
            var lastInbound: Instant? = origin.sentAt
            var lastOutbound: Instant? = null

            if (contact != null) {
                rows.forEach { row ->
                    val threadId = row[0] as UUID
                    if (threadId != origin.threadId) return@forEach
                    val sentAt = row[4] as Instant
                    if (sentAt < origin.sentAt) return@forEach

                    when (row[1] as CommDirection) {
                        CommDirection.INBOUND ->
                            if ((row[2] as String?) == contact && sentAt.isAfter(lastInbound)) lastInbound = sentAt
                        CommDirection.OUTBOUND ->
                            if ((row[3] as String?)?.contains(contact) == true &&
                                (lastOutbound == null || sentAt.isAfter(lastOutbound))
                            ) lastOutbound = sentAt
                    }
                }
            }
            lead.id to LeadConversationState.of(lastInboundAt = lastInbound, lastOutboundAt = lastOutbound)
        }.toMap()
    }

    private fun originMessage(lead: LeadEntity): CommMessageEntity? = originMessages(listOf(lead))[lead.id]

    private fun originMessages(leads: Collection<LeadEntity>): Map<UUID, CommMessageEntity> {
        val byMessage = extractionRepository.findByLeadIdIn(leads.map { it.id })
            .mapNotNull { extraction -> extraction.leadId?.let { it to extraction.messageId } }
            .toMap()
        if (byMessage.isEmpty()) return emptyMap()

        val messages = messageRepository.findAllById(byMessage.values.distinct()).associateBy { it.id }
        return byMessage.mapNotNull { (leadId, messageId) ->
            messages[messageId]?.let { leadId to it }
        }.toMap()
    }

    /** Kontakt leada, o ile jest adresem e-mail. Telefonu nie da się dopasować w wątku. */
    private fun emailContact(lead: LeadEntity): String? =
        lead.contactIdentifier.trim().lowercase().takeIf { it.contains('@') }

    private fun Instant.isAfter(other: Instant?): Boolean = other == null || this > other
}
