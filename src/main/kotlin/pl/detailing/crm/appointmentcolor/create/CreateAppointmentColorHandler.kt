package pl.detailing.crm.appointmentcolor.create

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.shared.AppointmentColorId
import java.time.Instant

@Service
class CreateAppointmentColorHandler(
    private val validatorComposite: CreateAppointmentColorValidatorComposite,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    @Transactional
    suspend fun handle(command: CreateAppointmentColorCommand): CreateAppointmentColorResult =
        withContext(Dispatchers.IO) {
            validatorComposite.validate(command)

            val colorId = AppointmentColorId.random()
            val now = Instant.now()

            val domain = AppointmentColorEntity.AppointmentColorDomain(
                id = colorId,
                studioId = command.studioId,
                name = command.name,
                hexColor = command.hexColor,
                isActive = true,
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = now,
                updatedAt = now
            )

            val entity = AppointmentColorEntity.fromDomain(domain)
            appointmentColorRepository.save(entity)

            CreateAppointmentColorResult(
                colorId = colorId,
                name = command.name,
                hexColor = command.hexColor
            )
        }
}

data class CreateAppointmentColorResult(
    val colorId: AppointmentColorId,
    val name: String,
    val hexColor: String
)
