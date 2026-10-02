package pl.detailing.crm.user.presence

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * „Ostatnio w aplikacji" i „Ostatnie logowanie" w oknie pracownika (V174).
 *
 * Aktywność zapisujemy z dokładnością do godziny, tak jak `lastSeenAt` tabletów
 * do podpisu: okno pracownika pokazuje „dziś, 7:58", a nie stoper, więc zapis przy
 * każdym zapytaniu API byłby UPDATE-em wiersza `users` przy każdym kliknięciu.
 * Pamięć ostatniego zapisu jest lokalna dla instancji - przy kilku instancjach
 * zapis wypada najwyżej raz na godzinę na każdą z nich, co wciąż jest niczym.
 *
 * Błąd zapisu nigdy nie psuje zapytania, w którym wypadł: to informacja dla kadr,
 * a nie warunek działania aplikacji.
 */
@Service
class UserPresenceService(
    private val userRepository: UserRepository
) {
    companion object {
        val SEEN_RESOLUTION: Duration = Duration.ofHours(1)
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val lastWrite = ConcurrentHashMap<UUID, Instant>()

    /** Udane logowanie hasłem albo PIN-em. Zapisuje zawsze - logowań jest kilka dziennie. */
    fun recordLogin(userId: UUID, now: Instant = Instant.now()) {
        runCatching { userRepository.markLoggedIn(userId, now) }
            .onSuccess { lastWrite[userId] = now }
            .onFailure { logger.warn("Could not record login [userId={}]: {}", userId, it.message) }
    }

    /** Zapytanie zalogowanego użytkownika. Zapisuje, gdy od poprzedniego zapisu minęła godzina. */
    fun touch(userId: UUID, now: Instant = Instant.now()) {
        val previous = lastWrite[userId]
        if (previous != null && Duration.between(previous, now) < SEEN_RESOLUTION) return
        // Rezerwacja przed zapisem: równoległe zapytania tej samej osoby nie zapiszą po kilka razy.
        if (previous == null) {
            if (lastWrite.putIfAbsent(userId, now) != null) return
        } else if (!lastWrite.replace(userId, previous, now)) {
            return
        }
        runCatching { userRepository.markSeen(userId, now) }
            .onFailure {
                // Następne zapytanie spróbuje jeszcze raz, zamiast czekać godzinę.
                lastWrite.remove(userId, now)
                logger.warn("Could not record activity [userId={}]: {}", userId, it.message)
            }
    }
}
