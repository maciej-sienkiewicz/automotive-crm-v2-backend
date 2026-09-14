package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface InstagramProfileRepository : JpaRepository<InstagramProfileEntity, UUID> {

    fun findByUsername(username: String): InstagramProfileEntity?

    fun existsByUsername(username: String): Boolean

    /**
     * Zwraca listę unikalnych username'ów, które mają status ACTIVE
     * w przynajmniej jednym studiu. Wywoływane przez scheduler.
     */
    @Query("""
        SELECT ip FROM InstagramProfileEntity ip
        WHERE EXISTS (
            SELECT sip.id FROM StudioInstagramProfileEntity sip
            WHERE sip.profileId = ip.id
            AND sip.status = pl.detailing.crm.shared.InstagramProfileStatus.ACTIVE
        )
    """)
    fun findAllActiveDistinct(): List<InstagramProfileEntity>

    /**
     * Profile obserwowane przez kogokolwiek i mające wskazaną stronę na Facebooku —
     * tylko te da się sprawdzić w Bibliotece reklam Meta.
     */
    @Query("""
        SELECT ip FROM InstagramProfileEntity ip
        WHERE ip.facebookPageId IS NOT NULL
        AND EXISTS (
            SELECT sip.id FROM StudioInstagramProfileEntity sip
            WHERE sip.profileId = ip.id
            AND sip.status = pl.detailing.crm.shared.InstagramProfileStatus.ACTIVE
        )
    """)
    fun findAllWithFacebookPage(): List<InstagramProfileEntity>

    /**
     * Profile, których link w bio zawiera podaną domenę — zgrubne sito po stronie bazy.
     * Dokładne porównanie hostów robi wołający: LIKE trafi też w „niecarslab.pl”.
     */
    @Query("""
        SELECT ip FROM InstagramProfileEntity ip
        WHERE ip.externalUrl IS NOT NULL
        AND LOWER(ip.externalUrl) LIKE CONCAT('%', LOWER(:domain), '%')
    """)
    fun findByExternalUrlLike(@Param("domain") domain: String): List<InstagramProfileEntity>
}
