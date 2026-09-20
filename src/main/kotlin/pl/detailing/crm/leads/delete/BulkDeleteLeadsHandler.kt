package pl.detailing.crm.leads.delete

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Usunięcie wielu leadów naraz — porządki w kolejce, nie operacja księgowa.
 *
 * ── Dlaczego to NIE jest jedna transakcja ──────────────────────────────────
 *
 * Bo część zaznaczonych spraw może się nie dać usunąć i jest to stan NORMALNY,
 * a nie awaria: lead z wizytą jest chroniony ([DeleteLeadHandler]), lead mógł
 * zniknąć sekundę wcześniej z drugiej przeglądarki. W jednej transakcji jedna
 * taka sprawa wycofywałaby usunięcie pozostałych dziewięciu i użytkownik dostałby
 * komunikat o błędzie po tym, jak zaznaczył, potwierdził i policzył na to, że
 * kolejka się przerzedziła.
 *
 * Każdy lead ma więc własną transakcję (wywołanie przez proxy [DeleteLeadHandler],
 * ta klasa świadomie NIE jest `@Transactional`), a wynik zbiorczy mówi wprost,
 * co przeszło i co nie. Pominięcia wracają z powodem, bo „nie udało się usunąć
 * 2 spraw" bez powodu jest komunikatem, z którym nie da się nic zrobić.
 *
 * ── Kolejność ──────────────────────────────────────────────────────────────
 *
 * Idziemy po identyfikatorach w kolejności, w jakiej przyszły z interfejsu, czyli
 * w kolejności kolejki. Dzięki temu przy przerwaniu w połowie (błąd sieci, restart)
 * usunięte jest to, co użytkownik widział na górze listy, a nie losowa garść.
 */
@Service
class BulkDeleteLeadsHandler(
    private val deleteLeadHandler: DeleteLeadHandler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(
        studioId: StudioId,
        leadIds: List<UUID>,
        userId: UUID,
        userName: String,
        deleteAppointments: Boolean
    ): BulkDeleteResult {
        if (leadIds.isEmpty()) throw ValidationException("Nie wskazano żadnej sprawy do usunięcia")
        if (leadIds.size > MAX_BATCH) {
            throw ValidationException("Naraz można usunąć najwyżej $MAX_BATCH spraw")
        }

        val skipped = mutableListOf<SkippedLead>()
        var deleted = 0

        // distinct(), bo ta sama sprawa potrafi trafić na listę dwa razy: zaznaczenie
        // jest trzymane po stronie interfejsu i przeżywa odświeżenie listy.
        for (leadId in leadIds.distinct()) {
            try {
                deleteLeadHandler.handle(
                    studioId = studioId,
                    leadId = leadId,
                    userId = userId,
                    userName = userName,
                    deleteAppointment = deleteAppointments
                )
                deleted += 1
            } catch (e: ConflictException) {
                // Najczęściej: lead ma wizytę. To jest reguła domenowa, nie błąd.
                skipped += SkippedLead(leadId.toString(), e.message ?: "Nie można usunąć tej sprawy")
            } catch (e: NotFoundException) {
                // Ktoś usunął ją wcześniej — z drugiej karty albo z panelu sprawy.
                skipped += SkippedLead(leadId.toString(), e.message ?: "Nie znaleziono sprawy")
            }
        }

        log.info(
            "[LEADS] Usuwanie zbiorcze w studiu {}: {} usuniętych, {} pominiętych (rezerwacje: {})",
            studioId.value, deleted, skipped.size, if (deleteAppointments) "usuwane" else "zostają"
        )
        return BulkDeleteResult(requested = leadIds.distinct().size, deleted = deleted, skipped = skipped)
    }

    private companion object {
        /**
         * Sufit jednego żądania. Nie ma go po to, żeby chronić bazę — dziesięć spraw
         * kasuje się w ułamku sekundy — tylko po to, żeby żądanie miało przewidywalny
         * czas odpowiedzi. Porządki na tysiącu leadów to zadanie dla eksportu i importu,
         * a nie dla zaznaczania myszką.
         */
        const val MAX_BATCH = 100
    }
}

data class BulkDeleteResult(
    /** Ile spraw wskazał użytkownik (po odsianiu duplikatów). */
    val requested: Int,
    val deleted: Int,
    /** Sprawy, których nie usunięto, z powodem dla każdej. Puste przy pełnym powodzeniu. */
    val skipped: List<SkippedLead>
)

data class SkippedLead(val leadId: String, val reason: String)
