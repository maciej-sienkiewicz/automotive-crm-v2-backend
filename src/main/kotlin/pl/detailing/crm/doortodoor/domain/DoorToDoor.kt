package pl.detailing.crm.doortodoor.domain

import pl.detailing.crm.shared.DoorToDoorId
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant

enum class DoorToDoorStatus {
    SCHEDULED,
    IN_PICKUP,
    PICKED_UP,
    IN_DELIVERY,
    DELIVERED
}

data class DoorToDoorAddress(
    val city: String,
    val street: String
)

data class DoorToDoor(
    val id: DoorToDoorId,
    val studioId: StudioId,
    val visitId: VisitId,
    /**
     * Czy usługa jest zlecona dla tej wizyty.
     *
     * Osobne pole, a nie samo istnienie rekordu: klient bywa niezdecydowany
     * ("jednak podjadę sam"), a adresy, kierowca i termin mają przetrwać takie
     * wyłączenie — po ponownym włączeniu nikt nie przepisuje ich od nowa.
     * Kasowanie rekordu zabierałoby też historię audytu.
     */
    val enabled: Boolean,
    val pickupAddress: DoorToDoorAddress,
    val deliveryAddress: DoorToDoorAddress,
    val notes: String?,
    val status: DoorToDoorStatus,
    /** Pracownik studia, który wiezie pojazd. null = jeszcze nieprzypisany. */
    val driverId: EmployeeId?,
    /**
     * Imię i nazwisko kierowcy zapisane W MOMENCIE przypisania.
     *
     * Migawka, a nie join przy odczycie: po zwolnieniu pracownika historyczna
     * wizyta ma nadal pokazywać, kto wiózł auto. Świeżą nazwę i tak bierzemy
     * z kartoteki przy każdym zapisie, więc literówka w nazwisku poprawia się
     * przy najbliższej edycji.
     */
    val driverName: String?,
    /** Umówiony termin dostarczenia. null = jeszcze nieustalony. */
    val scheduledAt: Instant?,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)
