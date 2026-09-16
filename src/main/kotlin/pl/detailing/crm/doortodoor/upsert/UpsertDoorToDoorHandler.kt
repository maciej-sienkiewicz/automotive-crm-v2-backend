package pl.detailing.crm.doortodoor.upsert

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.doortodoor.domain.DoorToDoorAddress
import pl.detailing.crm.doortodoor.domain.DoorToDoorStatus
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorEntity
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.DoorToDoorId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class UpsertDoorToDoorHandler(
    private val doorToDoorRepository: DoorToDoorRepository,
    private val visitRepository: VisitRepository,
    private val employeeRepository: EmployeeRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: UpsertDoorToDoorCommand): DoorToDoor =
        withContext(Dispatchers.IO) {
            val now = Instant.now()

            /*
             * Adres jest albo kompletny, albo pusty: samo miasto nikogo nie
             * dowiezie, a ulica bez miasta nie nadaje sie do nawigacji. Front
             * waliduje to samo, ale kontrakt nie moze na tym polegac - ten
             * endpoint wolaja tez inne sciezki.
             */
            requireCompleteOrEmpty(command.pickupCity, command.pickupStreet, "odbioru")
            requireCompleteOrEmpty(command.deliveryCity, command.deliveryStreet, "dostarczenia")

            /*
             * Door to Door to JEDEN LUB DWA odcinki, nie zawsze oba. Klient moze
             * chciec samego odbioru ("zabierzcie auto sprzed domu, wroce po nie
             * sam") albo samego dostarczenia ("przywioze rano, odwiezcie do
             * biura"). Wczesniej wymagany byl adres dostarczenia, co blokowalo
             * ten pierwszy wariant - dlatego warunek brzmi: co najmniej jeden
             * kompletny adres przy wlaczonej usludze.
             */
            if (command.enabled) {
                val hasPickup = command.pickupCity.isNotBlank() && command.pickupStreet.isNotBlank()
                val hasDelivery = command.deliveryCity.isNotBlank() && command.deliveryStreet.isNotBlank()
                require(hasPickup || hasDelivery) {
                    "Zlecony Door to Door wymaga adresu odbioru albo dostarczenia"
                }
            }

            /*
             * Kierowca musi nalezec DO TEGO studia. Bez tego dalo by sie przypisac
             * cudzego pracownika, podajac jego UUID - a jego nazwisko wrocilo by
             * w odpowiedzi jako driverName.
             */
            val driver = command.driverId?.let { id ->
                employeeRepository.findByIdAndStudioId(id.value, command.studioId.value)
                    ?: throw IllegalArgumentException("Pracownik nie nalezy do tego studia")
            }
            val driverName = driver?.let { "%s %s".format(it.firstName, it.lastName).trim() }

            val existing = doorToDoorRepository.findByVisitIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            )

            /* Czytane przed mutacja: `existing` to zarzadzana encja, wiec po
               podstawieniu nowych wartosci stara nazwa juz by nie istniala. */
            val existingDriverName = existing?.driverName

            val entity = if (existing != null) {
                existing.enabled = command.enabled
                existing.pickupCity = command.pickupCity
                existing.pickupStreet = command.pickupStreet
                existing.deliveryCity = command.deliveryCity
                existing.deliveryStreet = command.deliveryStreet
                existing.notes = command.notes
                existing.driverId = command.driverId?.value
                /* Zdjecie kierowcy czysci tez migawke - inaczej zostaloby
                   nazwisko bez przypisania i nie dalo by sie odroznic "nikt nie
                   przypisany" od "przypisany ktos, kogo juz nie ma". */
                existing.driverName = driverName
                existing.scheduledAt = command.scheduledAt
                existing.updatedBy = command.userId.value
                existing.updatedAt = now
                existing
            } else {
                DoorToDoorEntity.fromDomain(
                    DoorToDoor(
                        id = DoorToDoorId.random(),
                        studioId = command.studioId,
                        visitId = command.visitId,
                        enabled = command.enabled,
                        pickupAddress = DoorToDoorAddress(command.pickupCity, command.pickupStreet),
                        deliveryAddress = DoorToDoorAddress(command.deliveryCity, command.deliveryStreet),
                        notes = command.notes,
                        status = DoorToDoorStatus.SCHEDULED,
                        driverId = command.driverId,
                        driverName = driverName,
                        scheduledAt = command.scheduledAt,
                        createdBy = command.userId,
                        updatedBy = command.userId,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }

            val saved = doorToDoorRepository.save(entity)

            val visitNumber = visitRepository
                .findByIdAndStudioId(command.visitId.value, command.studioId.value)
                ?.visitNumber

            auditService.log(
                LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName,
                    module = AuditModule.DOOR_TO_DOOR,
                    entityId = command.visitId.value.toString(),
                    entityDisplayName = visitNumber,
                    action = if (existing != null) AuditAction.DOOR_TO_DOOR_UPDATED else AuditAction.DOOR_TO_DOOR_ADDED,
                    changes = listOfNotNull(
                        FieldChange("enabled", existing?.enabled?.toString(), command.enabled.toString()),
                        /* Kto wiezie i na kiedy to ustalenia z klientem, wiec
                           zostawiaja slad w audycie tak samo jak adresy. */
                        FieldChange("driver", existingDriverName, driverName ?: "nieprzypisany"),
                        command.scheduledAt?.let { FieldChange("scheduledAt", null, it.toString()) },
                        FieldChange("pickupAddress", null, "${command.pickupStreet}, ${command.pickupCity}"),
                        FieldChange("deliveryAddress", null, "${command.deliveryStreet}, ${command.deliveryCity}")
                    ),
                    metadata = emptyMap()
                )
            )

            saved.toDomain()
        }

    private fun requireCompleteOrEmpty(city: String, street: String, label: String) {
        require(city.isNotBlank() == street.isNotBlank()) {
            "Adres %s wymaga miasta i ulicy albo musi zostac pusty".format(label)
        }
    }
}
