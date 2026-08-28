package pl.detailing.crm.appointmentcolor.archive

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * Archiwizacja koloru: znika z list wyboru, ale wizyty, które go używają,
 * zachowują swoje oznaczenie.
 *
 * Flaga `is_active` istniała od początku (lista ma filtr „Pokaż archiwalne"),
 * tyle że nic jej nigdy nie zmieniało — jedyną drogą pozbycia się koloru było
 * twarde usunięcie, które zabiera oznaczenie także z historii.
 */
@Service
class ArchiveAppointmentColorHandler(
    private val appointmentColorRepository: AppointmentColorRepository
) {
    @Transactional
    suspend fun setArchived(
        colorId: AppointmentColorId,
        studioId: StudioId,
        userId: UserId,
        archived: Boolean
    ) = withContext(Dispatchers.IO) {
        val entity = appointmentColorRepository.findByIdAndStudioId(colorId.value, studioId.value)
            ?: throw EntityNotFoundException("Kolor rezerwacji nie został znaleziony")

        if (entity.isActive == !archived) return@withContext

        entity.isActive = !archived
        // Domyślny musi być wybieralny: archiwizacja zdejmuje tę rolę zamiast
        // zostawiać wizard z kolorem, którego nie ma na liście.
        if (archived) entity.isDefault = false
        entity.updatedBy = userId.value
        entity.updatedAt = Instant.now()

        appointmentColorRepository.save(entity)
        Unit
    }
}
