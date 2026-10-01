package pl.detailing.crm.shared.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/**
 * Blokada wiersza (`SELECT … FOR UPDATE`) RAZEM ze świeżym stanem encji.
 *
 * Zapytanie z `@Lock(PESSIMISTIC_WRITE)` blokuje wiersz, ale gdy encja jest już w kontekście
 * persystencji, Hibernate zwraca tę samą instancję i odrzuca odczytany wiersz — stan zostaje
 * sprzed blokady. Przy open-session-in-view (domyślnie włączonym w tej aplikacji) kontekst
 * żyje przez całe żądanie HTTP: checkout czytał studio przy wycenie, a „decyzja pod blokadą"
 * kilka milisekund później szła na tym samym, nieświeżym obiekcie. Tak samo webhook P24:
 * zamówienie i notyfikacja wczytane w kroku decyzji wracały w kroku zapisu płatności bez
 * zmian wprowadzonych w międzyczasie przez inny wątek (przegląd planu naprawczego).
 *
 * `flush` przed `refresh`: zmiany tej transakcji (np. nowe daty okresu przed ponowną
 * blokadą studia w [pl.detailing.crm.subscription.entitlement.EntitlementService]) mają trafić
 * do bazy, a nie zniknąć przy odświeżeniu.
 */
object FreshRowLock {

    fun <T : Any> lock(entityManager: EntityManager, type: Class<T>, id: Any): T? {
        val entity = entityManager.find(type, id) ?: return null
        entityManager.flush()
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)
        return entity
    }
}
