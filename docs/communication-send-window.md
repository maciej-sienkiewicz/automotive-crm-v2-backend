# Okno wysyłki komunikacji z klientem (12:00–18:00)

## Reguła

Każdy SMS i e-mail do klienta wychodzi wyłącznie w godzinach 12:00–18:00 czasu
warszawskiego (obie granice włącznie, z dokładnością do minuty). Poza tymi godzinami
wiadomość jest **przyjmowana i kolejkowana**, a wychodzi przy najbliższym otwarciu
okna: dziś o 12:00 (jeśli jest jeszcze przed) albo jutro o 12:00 (jeśli już po).

Regułę egzekwuje `OutboundCommunicationGateway` — jedyne miejsce, przez które
przechodzi komunikacja do klienta — więc obejmuje wszystkie ścieżki naraz: automaty,
kampanie, przyciski w UI, handlery zdarzeń. Nowa ścieżka wysyłki dostaje ją za darmo.

Konfiguracja (`application.properties`, env `COMMUNICATION_SEND_WINDOW_*`):

| Właściwość | Domyślnie | Znaczenie |
|---|---|---|
| `communication.send-window.enabled` | `true` | `false` wyłącza regułę (każda wiadomość idzie od razu) |
| `communication.send-window.zone` | `Europe/Warsaw` | strefa, w której liczone są godziny |
| `communication.send-window.opens-at` | `12:00` | pierwsza dozwolona minuta |
| `communication.send-window.closes-at` | `18:00` | ostatnia dozwolona minuta |

## Co omija kolejkę (`DeliveryPolicy.IMMEDIATE`)

Wyjątek jest jawny w miejscu wywołania i ma przy sobie uzasadnienie. Dziś są to:

| Ścieżka | Dlaczego od razu |
|---|---|
| SMS z linkiem do podpisu dokumentu (`RequestSignatureHandler`) | klient stoi przy ladzie, token ma krótką ważność |
| SMS z prośbą o akceptację dodatkowych usług (`RequestUpsellServicesHandler`) | studio czeka z pracą na odpowiedź |
| SMS z pytaniem o zmianę zakresu usług (`SmsConsentService`) | jak wyżej |
| Przypomnienie PRE_VISIT „za godzinę wizyta" (`SmsAutomationScheduler`) | zakotwiczone w godzinie wizyty; odłożone przyszłoby po fakcie |
| Link do podpisu pracownika (`UserSignatureLinkService`) | odbiorcą jest personel, link żyje 30 min |
| Wysyłka testowa z edytora kampanii, próba generalna (rehearsal) | odbiorcą jest sam użytkownik |

Wszystko inne — potwierdzenie rezerwacji i zmiany terminu, e-mail powitalny, gotowość
do odbioru (SMS i e-mail), link do karty wizyty / rezerwacji, podziękowanie po wizycie,
przypomnienia po usłudze, kampanie, SMS napisany ręcznie z karty klienta, zestawienia
dla kontrahentów — czeka na okno.

Żeby przenieść ścieżkę między grupami, wystarczy dodać albo zdjąć
`delivery = DeliveryPolicy.IMMEDIATE` w jej wywołaniu bramki.

## Jak to działa

```
handler ──► OutboundCommunicationGateway.sendSms / sendEmail
              1. moduł (entitlement)        ─ blokada → failure, bez kolejki
              2. zgoda marketingowa (kampanie)
              3. okno wysyłki               ─ poza oknem → outbound_message_queue,
                                              wynik: success=true, queued=true, scheduledFor
              4. przekierowanie / whitelista / kredyty / dostawca   (tylko w oknie)

OutboundMessageDispatcher (co minutę, tylko w oknie)
   ├─ QUEUED z terminem ≤ teraz → SENDING (atomowo, UPDATE ... WHERE status='QUEUED')
   ├─ gateway.deliverQueued(...) → pełna bramka od kroku 1, z IMMEDIATE
   ├─ sukces → SENT; błąd dostawcy → QUEUED za 5 min (max 3 próby) → FAILED;
   │  brak kredytów → FAILED od razu
   └─ communication_log: wpis QUEUED → SENT / FAILED (ten sam wiersz, `queued_message_id`)
```

* Wszystkie kontrole bramki (moduł, zgoda, przekierowanie, whitelista, kredyty) są
  wykonywane **w chwili faktycznej wysyłki**, nie w chwili odłożenia. Kredyt jest
  pobierany dopiero wtedy.
* Treść jest zamrożona przy odłożeniu — zmiana szablonu nie przepisuje wiadomości,
  którą pracownik już „wysłał".
* Każdy krok stanu ma własną transakcję; wywołanie dostawcy nie trzyma połączenia z bazą.
* Wiersz zawieszony w SENDING dłużej niż 15 min (restart w trakcie wysyłki) trafia do
  FAILED, a nie do ponowienia: nie wiemy, czy dostawca go dostał, a dubel SMS-a kosztuje.

## Co widzi użytkownik

* **Dziennik komunikacji** (`communication_log`): nowy status `QUEUED`. Po wysyłce ten sam
  wpis zmienia się w `SENT` / `FAILED`, a `sent_at` dostaje chwilę faktycznej wysyłki.
  Frontend powinien pokazywać `QUEUED` jako „w kolejce, wyjdzie o …".
* **Aktywność** (audyt): `SMS_QUEUED` / `EMAIL_QUEUED` przy odłożeniu, a `SMS_SENT` /
  `SMS_FAILED` (odpowiednio e-mail) przy faktycznej wysyłce.
* **SMS z karty klienta**: odpowiedź `POST /customers/{id}/sms` ma dodatkowe pola
  `queued` i `scheduledFor`.
* **Przypomnienia planowane ręcznie** i **podziękowania po wizycie** dostają termin
  dociągnięty do okna już przy zapisie, więc UI od razu pokazuje prawdziwą godzinę.

## Metryki

`communication.deferred` (odłożone), `communication.queued`, `communication.queue.sent`,
`communication.queue.failed` — z tagami `channel` / `category`.

## Schemat

Tabele `outbound_message_queue`, `outbound_message_attachment` i kolumna
`communication_log.queued_message_id` — migracja `V117__outbound_message_queue.sql`
(Hibernate `ddl-auto=update` zakłada je też sam). Nowa stała `QUEUED` w
`communication_log.status` wymaga zdjęcia starego CHECK-a — robi to
`EnumCheckConstraintDropper` przy starcie, migracja ma to samo dla środowisk z wyłączonym
dropperem.

## Rezerwacje całodniowe i `{{godzina}}`

Rezerwacja całodniowa zaczyna się o północy, więc `{{godzina}}` renderowało „00:00".
Od teraz `MessageTemplateRenderer.scheduleValues(moment, allDay = true)` zostawia
`{{godzina}}` pustą, a renderer wycina razem z nią zwrot, który ją zapowiadał
(„o godz.", „o godzinie", „godz.", „o", także z przecinkiem przed):

| Szablon | Wizyta o 14:30 | Rezerwacja całodniowa |
|---|---|---|
| `dnia {{data}} o godz. {{godzina}}.` | `dnia 09.09.2026 o godz. 14:30.` | `dnia 09.09.2026.` |
| `{{data}}, godz. {{godzina}}` | `09.09.2026, godz. 14:30` | `09.09.2026` |

Flaga pochodzi z rezerwacji (`AppointmentEntity.isAllDay`). Wiadomości renderowane
z wizyty (e-mail powitalny, gotowość do odbioru, karta wizyty, podziękowanie, automat
POST_VISIT / DELAYED_REMINDER) czytają ją przez `AppointmentAllDayLookup` albo przez
JOIN na rezerwację w zapytaniu automatu. Studio nie musi zmieniać szablonów.
