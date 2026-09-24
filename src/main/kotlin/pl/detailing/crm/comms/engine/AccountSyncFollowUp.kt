package pl.detailing.crm.comms.engine

import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity

/**
 * Krok wykonywany po udanej synchronizacji skrzynki, pod tą samą blokadą konta —
 * nic w tym czasie nie wpina nowej poczty do jej wątków.
 *
 * Interfejs po stronie poczty, żeby silnik synchronizacji nie musiał wiedzieć, kto
 * z niego korzysta (dziś: rozplątywanie wielkich wątków formularzy w module leadów).
 * Błąd kroku nie przerywa synchronizacji ani pozostałych kroków.
 */
fun interface AccountSyncFollowUp {
    fun afterSync(account: MailAccountEntity)
}
