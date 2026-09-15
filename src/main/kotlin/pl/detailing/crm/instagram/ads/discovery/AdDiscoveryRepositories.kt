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

    /**
     * Strony reklamodawców obecne w cache — kandydaci do ustalenia profilu IG.
     *
     * Kolejność od najświeższej reklamy: firmy, które reklamują się TERAZ, są
     * tymi, które użytkownik zobaczy na pierwszej stronie tabeli, więc ich
     * nazwy warto poznać najpierw.
     */
    @Query(
        """
        SELECT a.pageId FROM AdDiscoveryAdEntity a
        GROUP BY a.pageId
        ORDER BY MAX(a.fetchedAt) DESC
        """
    )
    fun distinctPageIds(pageable: org.springframework.data.domain.Pageable): List<String>
}

@Repository
interface AdAreaSettingsRepository : JpaRepository<AdAreaSettingsEntity, UUID> {

    /**
     * Ustawienia rejonu wszystkich studiów, które go w ogóle wskazały — źródło fraz
     * do cyklicznego odświeżania. Studio bez miejscowości nie generuje ani jednego
     * wywołania do Meta, bo i tak nie ma czego filtrować po obszarze.
     */
    @Query("SELECT s FROM AdAreaSettingsEntity s WHERE s.locations <> ''")
    fun findAllConfigured(): List<AdAreaSettingsEntity>
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
