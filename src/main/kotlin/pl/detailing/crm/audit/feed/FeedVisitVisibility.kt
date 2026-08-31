package pl.detailing.crm.audit.feed

import java.util.UUID

/**
 * Które wizyty nie mają prawa pojawić się w Aktywności.
 *
 * Dziennik audytowy jest rejestrem: zdarzenie zapisane przy przyjęciu pojazdu zostaje
 * w nim na zawsze, także wtedy, gdy przyjęcie nie zostało dokończone. Aktywność jest
 * czymś innym — to relacja z tego, co w firmie NAPRAWDĘ się wydarzyło, i wiersz
 * „Rozpoczęto wizytę" prowadzący do wizyty w stanie DRAFT jest w niej po prostu
 * nieprawdą: wizyta nie ruszyła, a link otwiera rekord, którego nie da się prowadzić.
 *
 * Stąd ten port: moduł audytu nie wie nic o cyklu życia wizyty i wiedzieć nie musi —
 * pyta tylko, czego nie pokazywać. Implementacja mieszka po stronie wizyt
 * ([pl.detailing.crm.visit.infrastructure.DraftVisitFeedVisibility]).
 */
interface FeedVisitVisibility {

    /** Identyfikatory wizyt, których zdarzenia mają być odsiane z odczytu feedu. */
    fun hiddenVisitIds(studioId: UUID): List<UUID>
}
