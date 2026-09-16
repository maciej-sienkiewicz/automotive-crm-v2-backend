package pl.detailing.crm.doortodoor.upsert

import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant

data class UpsertDoorToDoorCommand(
    val studioId: StudioId,
    val visitId: VisitId,
    val userId: UserId,
    val userName: String,
    val enabled: Boolean,
    val pickupCity: String,
    val pickupStreet: String,
    val deliveryCity: String,
    val deliveryStreet: String,
    val notes: String?,
    /** Pracownik studia. null = kierowca nieprzypisany albo świadomie zdjęty. */
    val driverId: EmployeeId?,
    /** Termin dostarczenia. null = nieustalony albo świadomie wyczyszczony. */
    val scheduledAt: Instant?
)
