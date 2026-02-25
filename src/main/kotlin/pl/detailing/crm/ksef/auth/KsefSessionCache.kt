package pl.detailing.crm.ksef.auth

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

data class KsefSession(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenValidUntil: OffsetDateTime,
    val refreshTokenValidUntil: OffsetDateTime
) {
    fun isAccessTokenValid(): Boolean =
        OffsetDateTime.now().isBefore(accessTokenValidUntil.minusMinutes(2))
}

/**
 * In-memory cache of active KSeF sessions keyed by studio ID.
 * Sessions are short-lived JWTs issued by the KSeF API.
 */
@Component
class KsefSessionCache {

    private val sessions = ConcurrentHashMap<StudioId, KsefSession>()

    fun put(studioId: StudioId, session: KsefSession) {
        sessions[studioId] = session
    }

    fun get(studioId: StudioId): KsefSession? {
        val session = sessions[studioId] ?: return null
        if (!session.isAccessTokenValid()) {
            sessions.remove(studioId)
            return null
        }
        return session
    }

    fun invalidate(studioId: StudioId) {
        sessions.remove(studioId)
    }
}
