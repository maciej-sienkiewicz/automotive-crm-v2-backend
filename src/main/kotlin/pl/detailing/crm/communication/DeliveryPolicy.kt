package pl.detailing.crm.communication

/**
 * Kiedy wiadomość ma wyjść — teraz czy w godzinach, w których wolno pisać do klienta.
 *
 * Domyślnie obowiązuje [SEND_WINDOW]: poza oknem ([pl.detailing.crm.communication.window.SendWindow])
 * bramka nie woła dostawcy, tylko odkłada wiadomość do kolejki, a dispatcher wysyła ją
 * przy najbliższym otwarciu. Wywołujący dostaje wynik `success = true, queued = true`
 * z terminem — wiadomość jest przyjęta, tylko jeszcze nie wyszła.
 *
 * [IMMEDIATE] jest świadomym wyjątkiem, nie wygodą. Wolno go użyć wyłącznie tam, gdzie
 * czekanie do 12:00 unieważnia wiadomość:
 *  - klient stoi przy ladzie i czeka na link (podpis dokumentu, akceptacja usług,
 *    zgoda na zmianę zakresu) — pracownik nie może pracować, dopóki klient nie odpowie;
 *  - treść jest zakotwiczona w godzinie zdarzenia („za godzinę wizyta") i po zdarzeniu
 *    nie ma już czego przypominać;
 *  - odbiorcą jest personel studia albo sam użytkownik (link do podpisu pracownika,
 *    wysyłka testowa, rehearsal) — reguła o porze dotyczy klientów, nie nas samych.
 *
 * Każde użycie [IMMEDIATE] ma mieć przy sobie zdanie, dlaczego — i to w miejscu wywołania.
 */
enum class DeliveryPolicy {
    SEND_WINDOW,
    IMMEDIATE
}
