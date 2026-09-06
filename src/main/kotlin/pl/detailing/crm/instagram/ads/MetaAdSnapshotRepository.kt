package pl.detailing.crm.instagram.ads

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
interface MetaAdSnapshotRepository : JpaRepository<MetaAdSnapshotEntity, UUID> {

    fun findByAdArchiveIdIn(adArchiveIds: Collection<String>): List<MetaAdSnapshotEntity>

    fun findByAdArchiveId(adArchiveId: String): MetaAdSnapshotEntity?

    /**
     * Wszystko, co dotykało zadanego zakresu dat: reklama zaczęta przed oknem
     * i wciąż trwająca też należy do kalendarza tego okna.
     */
    fun findByProfileIdInAndDeliveryStartLessThanEqualAndDeliveryStopIsNull(
        profileIds: Collection<UUID>,
        start: LocalDate
    ): List<MetaAdSnapshotEntity>

    fun findByProfileIdInAndDeliveryStartLessThanEqualAndDeliveryStopGreaterThanEqual(
        profileIds: Collection<UUID>,
        start: LocalDate,
        stop: LocalDate
    ): List<MetaAdSnapshotEntity>

    fun findByProfileIdIn(profileIds: Collection<UUID>): List<MetaAdSnapshotEntity>

    /** Reklamy uruchomione w oknie — zdarzenie „uruchomił reklamę" w Pulsie. */
    fun findByProfileIdInAndDeliveryStartGreaterThanEqual(
        profileIds: Collection<UUID>,
        from: LocalDate
    ): List<MetaAdSnapshotEntity>

    /** Reklamy, których koniec wykryliśmy w oknie — zdarzenie „zakończył reklamę". */
    fun findByProfileIdInAndEndedDetectedAtGreaterThanEqual(
        profileIds: Collection<UUID>,
        from: Instant
    ): List<MetaAdSnapshotEntity>

    fun deleteByProfileId(profileId: UUID)

    fun deleteByDeliveryStartBefore(cutoff: LocalDate): Long
}
