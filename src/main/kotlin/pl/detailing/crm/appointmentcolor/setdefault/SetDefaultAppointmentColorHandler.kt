package pl.detailing.crm.appointmentcolor.setdefault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/**
 * Kolor domyślny studia — ten, który wizard wizyty ma zaznaczony od wejścia.
 *
 * Wyłączność jest tu regułą, nie konwencją: najpierw zdejmujemy flagę z
 * poprzedniego koloru, dopiero potem stawiamy na nowym, w jednej transakcji.
 * Odwrotna kolejność łamałaby częściowy indeks unikalny z V96.
 *
 * ## Dlaczego TransactionTemplate, a nie @Transactional
 *
 * Transakcja Springa żyje w ThreadLocal wątku, który ją otworzył. `withContext`
 * przenosi ciało funkcji na wątek puli IO, gdzie tego powiązania już nie ma, więc
 * `@Transactional` nad `suspend fun` nie obejmuje niczego — adnotacja jest, a
 * transakcji nie ma. Przy pojedynczym `save()` nie widać tego wcale (repozytorium
 * Spring Data otwiera własną transakcję na jedno wywołanie), ale zapytanie
 * `@Modifying` żąda transakcji wołającego i kończy się:
 *
 *     No EntityManager with actual transaction available for current thread
 *     - cannot reliably process 'flush' call
 *
 * Template otwiera transakcję już na wątku IO, czyli tam, gdzie faktycznie
 * wykonują się zapytania. Ten sam zabieg i z tego samego powodu stosuje
 * [pl.detailing.crm.visit.transitions.cancel.CancelDraftVisitHandler].
 */
@Service
class SetDefaultAppointmentColorHandler(
    private val appointmentColorRepository: AppointmentColorRepository,
    private val transactionTemplate: TransactionTemplate
) {
    suspend fun setDefault(colorId: AppointmentColorId, studioId: StudioId, userId: UserId) =
        withContext(Dispatchers.IO) {
            transactionTemplate.executeWithoutResult {
                val entity = appointmentColorRepository.findByIdAndStudioId(colorId.value, studioId.value)
                    ?: throw EntityNotFoundException("Kolor rezerwacji nie został znaleziony")

                // Kolor archiwalny nie pojawia się na liście wyboru, więc jako domyślny
                // dałby wizardowi wartość, której użytkownik nie widzi i nie umie zmienić.
                if (!entity.isActive) {
                    throw ValidationException("Kolor archiwalny nie może być domyślny — najpierw go przywróć")
                }

                appointmentColorRepository.clearDefaultForStudio(studioId.value, colorId.value)

                if (!entity.isDefault) {
                    entity.isDefault = true
                    entity.updatedBy = userId.value
                    entity.updatedAt = Instant.now()
                    appointmentColorRepository.save(entity)
                }
            }
        }

    /** Studio może nie mieć koloru domyślnego — wtedy wizard startuje z pustym polem. */
    suspend fun clearDefault(studioId: StudioId) =
        withContext(Dispatchers.IO) {
            transactionTemplate.executeWithoutResult {
                appointmentColorRepository.clearDefaultForStudio(studioId.value, null)
            }
        }
}
