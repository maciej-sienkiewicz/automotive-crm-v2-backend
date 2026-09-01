# Stub SDK KSeF (tylko do kompilacji testów)

Oficjalny klient KSeF (`pl.akmf.ksef-sdk:ksef-client`) jest publikowany wyłącznie
w GitHub Packages, które wymaga PAT-a z uprawnieniem `read:packages`. W środowiskach
bez takiego dostępu (sandboxy, świeże stanowisko dewelopera) nie da się przez to
uruchomić NAWET testów niezwiązanych z KSeF, bo nie zbuduje się classpath.

Ten katalog zawiera minimalne stuby typów SDK faktycznie używanych przez moduł
`pl.detailing.crm.ksef` — wyłącznie sygnatury, każda metoda rzuca
`UnsupportedOperationException`.

Użycie: `./gradlew test -PksefStub` — flaga podmienia zależność na te źródła.

Zasady:
- Build produkcyjny (`bootJar`) z flagą `ksefStub` jest ZABLOKOWANY w build.gradle.kts —
  stub nie ma prawa trafić do artefaktu.
- CI buduje i testuje na prawdziwym SDK (ma credentiale), więc dryf stubów nie ukryje
  błędu kompilacji; stuby aktualizuje się przy podbiciu wersji SDK, gdy `-PksefStub`
  przestanie się kompilować.
