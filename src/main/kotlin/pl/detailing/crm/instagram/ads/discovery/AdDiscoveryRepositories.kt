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


@Repository
interface AdvertiserBlockRepository : JpaRepository<AdvertiserBlockEntity, UUID> {

    /**
     * Wykluczenia obowiązujące dane studio: globalne (administratora) i własne.
     *
     * Jedno zapytanie zamiast dwóch, bo filtr tabeli potrzebuje obu naraz i nigdy
     * osobno — a dwa osobne odczyty dałyby dwa razy tę samą odpowiedź o niczym.
     */
    @Query("""
        SELECT b FROM AdvertiserBlockEntity b
        WHERE b.studioId IS NULL OR b.studioId = :studioId
    """)
    fun findEffectiveFor(@Param("studioId") studioId: UUID): List<AdvertiserBlockEntity>

    /** Sama czarna lista studia — do ekranu, gdzie da się ją cofnąć. */
    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID): List<AdvertiserBlockEntity>

    fun findByStudioIdAndPageId(studioId: UUID, pageId: String): AdvertiserBlockEntity?

    fun deleteByStudioIdAndPageId(studioId: UUID, pageId: String): Long

    fun countByStudioId(studioId: UUID): Long
}
