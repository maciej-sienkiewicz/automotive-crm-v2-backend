package pl.detailing.crm.subscription.entitlement

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.detailing.crm.shared.StudioId

/**
 * Unieważnia wpis `studio-entitlements` studia TERAZ i jeszcze raz PO zakończeniu transakcji.
 *
 * Dawne `@CacheEvict` czyściło cache w chwili wyjścia z metody — wewnątrz transakcji
 * zewnętrznej (scheduler, realizacja zamówienia), czyli PRZED commitem. Równoległe żądanie
 * wczytywało wtedy jeszcze stary stan z bazy i wpisywało go do cache'u na 5 minut: klient
 * po opłaconym upgradzie widział stary plan (audyt, J5). `@Cacheable` wewnątrz takiej
 * transakcji potrafiło z kolei wpisać stan NIEZATWIERDZONY, który po rollbacku zostawał.
 *
 * Drugie usunięcie po zakończeniu transakcji (commit i rollback) zamyka oba okna. Celowo
 * nie przez globalne `RedisCacheManager.transactionAware()`: to zmieniłoby moment eviction
 * we wszystkich cache'ach aplikacji (uprawnienia ról, tagi galerii), a nie tylko tutaj.
 */
@Component
class EntitlementCacheInvalidator(
    /** Opcjonalny: w wycinkach testowych bez cache'u (`@DataJpaTest`) menedżera nie ma. */
    private val cacheManagers: ObjectProvider<CacheManager>
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun evict(studioId: StudioId) {
        evictNow(studioId)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) = evictNow(studioId)
            })
        }
    }

    private fun evictNow(studioId: StudioId) {
        try {
            cacheManagers.ifAvailable?.getCache(CACHE_NAME)?.evict(studioId.toString())
        } catch (e: Exception) {
            // Redis niedostępny: wpis i tak wygaśnie po TTL, a odczyt bez cache'u idzie do bazy.
            logger.warn("Nie udało się usunąć {} z cache'u {}: {}", studioId, CACHE_NAME, e.message)
        }
    }

    companion object {
        const val CACHE_NAME = "studio-entitlements"
    }
}
