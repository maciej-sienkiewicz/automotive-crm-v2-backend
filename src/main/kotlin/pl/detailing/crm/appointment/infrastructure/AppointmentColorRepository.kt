package pl.detailing.crm.appointment.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AppointmentColorRepository : JpaRepository<AppointmentColorEntity, UUID> {

    @Query("SELECT ac FROM AppointmentColorEntity ac WHERE ac.id = :id AND ac.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): AppointmentColorEntity?

    @Query("SELECT ac FROM AppointmentColorEntity ac WHERE ac.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<AppointmentColorEntity>

    @Query("SELECT ac FROM AppointmentColorEntity ac WHERE ac.studioId = :studioId AND ac.isActive = true")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<AppointmentColorEntity>

    @Query("""
        SELECT ac FROM AppointmentColorEntity ac
        WHERE ac.studioId = :studioId AND ac.isDefault = true
    """)
    fun findDefaultByStudioId(@Param("studioId") studioId: UUID): AppointmentColorEntity?

    /**
     * Zdejmuje flagę domyślności ze wszystkich kolorów studia poza wskazanym.
     * Jedno UPDATE zamiast wczytywania listy: częściowy indeks unikalny z V96
     * nie pozwoli, żeby stary i nowy domyślny współistniały choćby przez chwilę
     * w obrębie transakcji.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AppointmentColorEntity ac
        SET ac.isDefault = false
        WHERE ac.studioId = :studioId AND ac.isDefault = true
        AND (:exceptId IS NULL OR ac.id != :exceptId)
    """)
    fun clearDefaultForStudio(
        @Param("studioId") studioId: UUID,
        @Param("exceptId") exceptId: UUID?
    ): Int

    @Query("""
        SELECT ac FROM AppointmentColorEntity ac
        WHERE ac.studioId = :studioId
        AND LOWER(ac.name) = LOWER(:name)
        AND (:excludeId IS NULL OR ac.id != :excludeId)
    """)
    fun findByStudioIdAndNameIgnoreCase(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String,
        @Param("excludeId") excludeId: UUID?
    ): AppointmentColorEntity?
}
