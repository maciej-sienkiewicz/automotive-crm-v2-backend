package pl.detailing.crm.employee.leaverequest.expiry

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sprząta wnioski, które utknęły:
 *
 *  - PENDING, którego termin rozpoczęcia minął bez decyzji → EXPIRED. Wniosek o urlop
 *    „od wczoraj" nie jest już o nic prośbą, a w kolejce udawałby, że decyzja wciąż
 *    ma sens. Znika z „Oczekujących" i trafia do „Rozpatrzonych", pliki zostają.
 *  - DRAFT starszy niż doba → usunięty razem z plikiem. Szkic bez podpisu nie jest
 *    dokumentem: pracownik zamknął kreator i nie wrócił.
 *
 * Bez rozproszonej blokady (w projekcie nie ma ShedLocka): każdy krok to warunkowy
 * UPDATE/DELETE „… WHERE status = …", więc dwie instancje przechodzące tę samą listę
 * nie zrobią niczego dwa razy, a wniosek złożony w międzyczasie nie zostanie ruszony.
 */
@Component
class LeaveRequestExpiryJob(
    private val repository: LeaveRequestRepository,
    private val documents: LeaveRequestDocumentService,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${crm.leave-requests.draft-ttl-hours:24}") private val draftTtlHours: Long
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val warsaw = ZoneId.of("Europe/Warsaw")

    companion object {
        private const val BATCH = 200
    }

    @Scheduled(cron = "\${crm.leave-requests.cleanup-cron:0 7 * * * *}")
    fun run() {
        runCatching { expireOverdue() }.onFailure { logger.error("Wygaszanie wniosków urlopowych nie powiodło się", it) }
        runCatching { deleteAbandonedDrafts() }.onFailure { logger.error("Sprzątanie szkiców wniosków nie powiodło się", it) }
    }

    fun expireOverdue(): Int {
        val today = LocalDate.now(warsaw)
        val overdue = repository.findPendingStartedBefore(today, PageRequest.of(0, BATCH))
        var expired = 0
        overdue.forEach { request ->
            val updated = transactionTemplate.execute {
                repository.markExpired(request.id, request.studioId, today, Instant.now())
            } ?: 0
            expired += updated
        }
        if (expired > 0) logger.info("Wnioski urlopowe: wygaszono {} oczekujących po terminie rozpoczęcia", expired)
        return expired
    }

    fun deleteAbandonedDrafts(): Int {
        val threshold = Instant.now().minus(Duration.ofHours(draftTtlHours))
        val drafts = repository.findDraftsCreatedBefore(threshold, PageRequest.of(0, BATCH))
        var deleted = 0
        drafts.forEach { draft ->
            // Najpierw wiersz, potem plik: gdy szkic został w międzyczasie złożony, DELETE
            // nic nie usunie, a plik (H1, na który powołuje się podpis) zostanie.
            val removed = transactionTemplate.execute { repository.deleteDraft(draft.id, draft.studioId) } ?: 0
            if (removed > 0) {
                runBlocking { documents.deleteQuietly(draft.documentS3Key) }
                deleted++
            }
        }
        if (deleted > 0) logger.info("Wnioski urlopowe: usunięto {} porzuconych szkiców starszych niż {} h", deleted, draftTtlHours)
        return deleted
    }
}
