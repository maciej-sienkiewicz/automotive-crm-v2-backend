package pl.detailing.crm.audit.domain

import pl.detailing.crm.shared.StudioId

/**
 * Dopina kontekst biznesowy (klient, pojazd, wizyta) do zdarzenia, którego autor
 * go nie podał.
 *
 * Filtr „wszystko wokół jednego obiektu" w feedzie czyta kolumny kontekstu —
 * a kilkanaście miejsc logujących zdarzenia rezerwacji i wizyt podawało id
 * klienta co najwyżej w metadanych, więc karta klienta nie widziała jego
 * rezerwacji. Zamiast poprawiać każdy z tych punktów z osobna (i pilnować
 * każdego następnego), kontekst dokleja się raz, w lejku [AuditService]:
 * moduł zdarzenia wie, w jakiej tabeli leży jego obiekt, i umie go rozwiązać.
 *
 * Implementacje mieszkają w modułach właścicielskich (rezerwacje, wizyty) —
 * audyt zna tylko ten interfejs. Rozwiązanie NIGDY nie może wywrócić zapisu
 * zdarzenia: [AuditService] woła je w runCatching, a null znaczy po prostu
 * „bez kontekstu", jak dotychczas.
 */
interface AuditContextResolver {

    /** @return kontekst dla zdarzenia albo null, gdy ten resolver go nie obsługuje. */
    fun resolve(studioId: StudioId, module: AuditModule, entityId: String): AuditContext?
}
