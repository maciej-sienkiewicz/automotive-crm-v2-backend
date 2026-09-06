package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
}
