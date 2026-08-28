package pl.detailing.crm.appointmentcolor.delete

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class DeleteAppointmentColorHandler(
    private val appointmentColorRepository: AppointmentColorRepository,
    private val appointmentRepository: AppointmentRepository,
    private val visitRepository: VisitRepository
) {
    /**
     * Usunięcie jest twarde, a `appointment_color_id` to zwykła kolumna bez klucza
     * obcego — skasowanie używanego koloru zostawiało rezerwacje i wizyty ze
     * wskaźnikiem donikąd (kalendarz rysował je wtedy bez oznaczenia). Dlatego
     * kolor w użyciu można tylko zarchiwizować.
     */
    @Transactional
    suspend fun handle(command: DeleteAppointmentColorCommand) =
        withContext(Dispatchers.IO) {
            val entity = appointmentColorRepository.findByIdAndStudioId(
                command.colorId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Kolor rezerwacji nie został znaleziony")

            val usages = appointmentRepository.countByAppointmentColorId(entity.id) +
                visitRepository.countByAppointmentColorId(entity.id)

            if (usages > 0) {
                throw ConflictException(
                    "Kolor jest używany przez $usages ${wizytOrRezerwacji(usages)} — zarchiwizuj go zamiast usuwać"
                )
            }

            appointmentColorRepository.delete(entity)
        }
}

/** Polska odmiana rzeczownika po liczebniku: 1 wizytę / 2-4 wizyty / 5+ wizyt. */
internal fun wizytOrRezerwacji(count: Long): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        count == 1L -> "wizytę lub rezerwację"
        lastTwo in 12..14 -> "wizyt lub rezerwacji"
        last in 2..4 -> "wizyty lub rezerwacje"
        else -> "wizyt lub rezerwacji"
    }
}
