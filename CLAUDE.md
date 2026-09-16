# CLAUDE.md — zasady, od których nie ma odstępstw

Ten plik czyta każdy agent AI przed dotknięciem kodu. Jest krótki celowo: zawiera
wyłącznie te reguły, których złamanie kosztuje pieniądze albo zaufanie klienta.

---

## 1. PIENIĄDZE: brutto, które ktoś ustalił, JEST brutto

> **Kwoty, którą wpisał człowiek, nie wolno policzyć po raz drugi.**
> Liczymy wyłącznie to, czego nikt nie ustalił.

### Dlaczego to jest reguła, a nie preferencja

Przejście brutto → netto → brutto **nie jest tożsamością**. Przy 23% VAT:

```
190000 gr brutto  →  190000 / 1,23 = 154471,54  →  154472 gr netto
154472 gr netto   →  154472 × 1,23 = 190000,56  →  190001 gr brutto
```

Kwota **1900,00 zł jest w tę stronę nieosiągalna**: 154471 gr daje 1899,99 zł,
154472 gr daje 1900,01 zł. Nie istnieje netto w groszach, z którego wyjdzie równo
1900,00 zł brutto. Siatka groszowa sprawia, że odwzorowanie netto → brutto nie
jest „na" — część kwot brutto jest przeskakiwana. Żadne „lepsze zaokrąglenie"
tego nie naprawi.

Skutek biznesowy: użytkownik wpisuje 1900,00 zł, zapisuje, wraca — i widzi
1900,01 zł. Cena podana klientowi „pływa", a na protokole wydania i fakturze
pojawia się kwota inna niż uzgodniona.

### Reguła kierunkowa

| Co wpisał człowiek | Co jest źródłem prawdy | Co wolno policzyć |
|---|---|---|
| **brutto** | brutto — przechowywane dokładnie | netto = `vatRate.netCentsFromGrossCents(brutto)` |
| **netto**  | netto — przechowywane dokładnie | brutto = `vatRate.calculateGrossAmount(netto)` |

Wielkość wpisana przez człowieka jest zapisywana bez zmian i **nigdy** nie jest
odtwarzana z drugiej.

### Jak to robić w kodzie

```kotlin
// DOBRZE — brutto podane przez klienta wygrywa, przeliczenie jest ostatecznością
val gross = vatRate.resolveGrossAmount(netAmount, command.basePriceGross)

// DOBRZE — brutto końcowe pozycji zna wyjątki gross-side i rabat zerowy
AppointmentLineItem.create(..., basePriceGross = serviceReq.basePriceGross?.let(Money::fromCents))
```

```kotlin
// ŹLE — zgubi grosz na każdej cenie wpisanej jako brutto
val gross = vatRate.calculateGrossAmount(netAmount)   // gdy basePriceGross jest dostępne
val vat   = vatRate.calculateVatAmount(netAmount)     // gdy brutto jest już znane
```

`calculateGrossAmount()` samo w sobie nie jest zakazane — jest **fallbackiem** dla
ceny podanej w netto. Zakazane jest użycie go tam, gdzie dokładne brutto jest pod
ręką.

### Konsekwencje, o których łatwo zapomnieć

- **VAT to różnica**, nie osobne mnożenie: `vat = brutto − netto`. Dlatego
  `totalVat` rezerwacji z ceną 1900,00 zł wynosi 35528 gr, a nie 35529 gr.
- **Tolerancja 1 grosza** w niezmiennikach (`AppointmentLineItem.init`,
  `resolveGrossAmount`) jest tam **celowo** i nie wolno jej zaostrzyć do zera —
  to ona przepuszcza legalne pary brutto/netto wpisane od strony brutto.
  Zaostrzenie jej wywali każdą cenę podaną w brutto.
- **Rabat liczony od netta** (PERCENT, FIXED_NET, SET_NET) zmienia kwotę bazową,
  więc brutto końcowe **należy** policzyć. `SET_GROSS`, `FIXED_GROSS` i rabat
  zerowy — nie wolno; tam dokładne brutto przechodzi dalej.
- **Dokładne brutto musi przejść przez każdą granicę**: request → command →
  domena → encja → response. Zgubione raz, nie odtworzy się już nigdy.

### Wzorce do skopiowania

- `shared/ValueClasses.kt` → `VatRate.resolveGrossAmount`, `netCentsFromGrossCents`
- `appointment/domain/Appointment.kt` → `AppointmentLineItem.calculateFinalGross`
- `visit/domain/Visit.kt` → `calculateFinalGross` (wyjątki gross-side i rabat zerowy)
- `service/update/UpdateServiceHandler.kt` → zapis pary netto/brutto z katalogu

### Zlecenie stałe

Gdy natrafisz na kod łamiący tę regułę — **napraw go**, nie tylko opisz. Jeśli
naprawa wykracza daleko poza zadanie, przy którym stoisz: napraw to, czego
dotykasz, a resztę wypisz wprost w odpowiedzi, z plikami i liniami. Nigdy nie
zostawiaj tego milcząco.

**Nie osłabiaj testów ani niezmienników, żeby przepuścić zmianę.** Jeśli
zaczynają przeszkadzać, to zmiana jest zła, a nie one.

---

## 2. Budowanie

`ksef-client` mieszka w prywatnym GitHub Packages i wymaga PAT-a
(`gpr.user`/`gpr.key` albo `GITHUB_ACTOR`/`GITHUB_TOKEN`). Bez nich `./gradlew`
przerywa na rozwiązywaniu zależności, zanim cokolwiek skompiluje — to nie jest
błąd w kodzie. Patrz komentarz w `build.gradle.kts`.
