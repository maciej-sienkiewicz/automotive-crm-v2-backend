package pl.detailing.crm.communication.template

import org.springframework.stereotype.Component
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import java.util.UUID

/**
 * Czy wizyta wzięła się z rezerwacji całodniowej.
 *
 * Wizyta przechowuje tylko `scheduledDate` (dla rezerwacji całodniowej — północ), a flagę
 * „cały dzień" ma wyłącznie rezerwacja. Każda wiadomość renderowana z wizyty
 * (e-mail powitalny, gotowość do odbioru, karta wizyty, podziękowanie) pyta tutaj, zanim
 * wstawi {{godzina}}. Rezerwacja mogła zostać w międzyczasie usunięta miękko — wizyta
 * i tak istnieje, więc czytamy również usunięte.
 */
@Component
class AppointmentAllDayLookup(private val appointmentRepository: AppointmentRepository) {

    fun isAllDay(appointmentId: UUID, studioId: UUID): Boolean =
        appointmentRepository.findByIdAndStudioIdIncludingDeleted(appointmentId, studioId)?.isAllDay ?: false
}
