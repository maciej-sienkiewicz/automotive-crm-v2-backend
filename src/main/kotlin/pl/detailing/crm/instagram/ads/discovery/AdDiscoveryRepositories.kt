package pl.detailing.crm.instagram.ads.discovery

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AdDiscoveryPhraseRepository : JpaRepository<AdDiscoveryPhraseEntity, UUID> {
    fun findByPhrase(phrase: String): AdDiscoveryPhraseEntity?
    fun findByPhraseIn(phrases: Collection<String>): List<AdDiscoveryPhraseEntity>
}

@Repository
interface AdDiscoveryAdRepository : JpaRepository<AdDiscoveryAdEntity, UUID> {
    fun findByPhraseIn(phrases: Collection<String>): List<AdDiscoveryAdEntity>

    @Modifying
    @Query("DELETE FROM AdDiscoveryAdEntity a WHERE a.phrase = :phrase")
    fun deleteByPhrase(@Param("phrase") phrase: String)
}

@Repository
interface AdLocationTrackingRepository : JpaRepository<AdLocationTrackingEntity, UUID> {

    /** Śledzenia jednego studia — pełna lista dla ekranu, najnowsze na górze. */
    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<AdLocationTrackingEntity>

    /** Jedno śledzenie w granicach studia — zapora przed sięganiem po cudze przez samo id. */
    fun findByIdAndStudioId(id: UUID, studioId: UUID): AdLocationTrackingEntity?

    /** Aktywne śledzenia wszystkich najemców — źródło fraz do cyklicznego odświeżania. */
    fun findByActiveTrue(): List<AdLocationTrackingEntity>

    fun deleteByIdAndStudioId(id: UUID, studioId: UUID): Long

    fun countByStudioId(studioId: UUID): Long
}
