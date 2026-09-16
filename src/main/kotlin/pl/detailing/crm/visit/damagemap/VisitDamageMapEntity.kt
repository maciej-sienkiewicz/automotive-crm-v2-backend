package pl.detailing.crm.visit.damagemap

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Punkty uszkodzeń wizyty — źródło prawdy dla mapy uszkodzeń.
 *
 * Osobna tabela, nie kolumny na `visits`: wizytę zapisuje się miejscami przez
 * `VisitEntity.fromDomain(visit)`, czyli przepisaniem całego agregatu. Kolumna,
 * której nie niesie model domenowy `Visit`, zostałaby wtedy wyzerowana — a punkty
 * uszkodzeń są dowodem w sporze „kto zrobił tę rysę". Patrz V135.
 */
@Entity
@Table(name = "visit_damage_maps")
class VisitDamageMapEntity(
    @Id
    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /**
     * Tablica punktów jako tekst JSON. Serializacja jest jawna, w
     * [VisitDamageMapStore] — tak samo jak przy imporcie klientów: mapowanie
     * strukturalne wiąże kształt tabeli z kształtem klasy, a wtedy dodanie pola
     * do [pl.detailing.crm.visit.domain.DamagePoint] przestaje być zmianą
     * kompatybilną wstecz i stara mapa przestaje się czytać.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "damage_points", nullable = false, columnDefinition = "jsonb")
    var damagePointsJson: String = "[]",

    @Column(name = "vehicle_type", length = 30)
    var vehicleType: String? = null,

    /** Klucz S3 PDF-a wygenerowanego z tej wersji punktów. */
    @Column(name = "document_s3_key", length = 500)
    var documentS3Key: String? = null,

    /** 1 = mapa z przyjęcia; każda aktualizacja w trakcie wizyty podnosi o jeden. */
    @Column(name = "revision", nullable = false)
    var revision: Int = 1,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now(),

    @Column(name = "updated_by", columnDefinition = "uuid")
    var updatedBy: UUID? = null,

    @Column(name = "updated_by_name", length = 200)
    var updatedByName: String? = null
)

@Repository
interface VisitDamageMapRepository : JpaRepository<VisitDamageMapEntity, UUID> {
    fun findByVisitIdAndStudioId(visitId: UUID, studioId: UUID): VisitDamageMapEntity?
}
