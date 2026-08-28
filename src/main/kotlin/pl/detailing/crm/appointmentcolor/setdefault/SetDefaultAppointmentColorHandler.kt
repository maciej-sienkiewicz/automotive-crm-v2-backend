package pl.detailing.crm.appointmentcolor.setdefault

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
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
 */
@Service
class SetDefaultAppointmentColorHandler(
    private val appointmentColorRepository: AppointmentColorRepository
) {
    @Transactional
    suspend fun setDefault(colorId: AppointmentColorId, studioId: StudioId, userId: UserId) =
        withContext(Dispatchers.IO) {
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

    /** Studio może nie mieć koloru domyślnego — wtedy wizard startuje z pustym polem. */
    @Transactional
    suspend fun clearDefault(studioId: StudioId) =
        withContext(Dispatchers.IO) {
            appointmentColorRepository.clearDefaultForStudio(studioId.value, null)
            Unit
        }
}
