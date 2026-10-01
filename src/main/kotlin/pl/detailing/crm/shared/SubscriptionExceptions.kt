package pl.detailing.crm.shared

/**
 * Studio nie może teraz korzystać z produktu (NO_PLAN, EXPIRED, karencja minęła).
 * → HTTP 403 z kodem `SUBSCRIPTION_INACTIVE` — tym samym, który zwraca
 * [pl.detailing.crm.config.SubscriptionInterceptor], więc frontend ma jeden sygnał paywalla
 * niezależnie od tego, czy zatrzymał go interceptor, czy kontrola modułu.
 */
class SubscriptionInactiveException(
    message: String = "Subskrypcja studia nie jest aktywna — opłać przedłużenie, aby korzystać z tej funkcji."
) : BusinessException(message)

/**
 * Operacja na subskrypcji niezgodna z jej bieżącym stanem (np. anulowanie downgrade'u,
 * który już wszedł w życie). → HTTP 409; [code] pozwala frontendowi odróżnić przypadki
 * bez parsowania komunikatu.
 */
class SubscriptionConflictException(
    val code: String,
    message: String
) : BusinessException(message)

/**
 * Bramka płatności jest nieskonfigurowana albo niedostępna. → HTTP 503.
 *
 * Istnieje po to, żeby brak poświadczeń Przelewy24 kończył się odmową, a nie — jak
 * wcześniej — realizacją zamówienia bez pobrania pieniędzy.
 */
class PaymentsUnavailableException(
    message: String = "Płatności są chwilowo niedostępne. Spróbuj ponownie za kilka minut."
) : BusinessException(message)
