package pl.detailing.crm.service.taxonomy

/**
 * Osie ROBOTY — drugi i trzeci wymiar klasyfikacji nazwy usługi, obok rodziny.
 *
 * Rodzina ([ServiceFamily]) grupuje po PRZEDMIOCIE, a wycena idzie po RZEMIOŚLE:
 * „Naprawa tapicerki drzwi" i „Wnętrze rozszerzone" to jedna rodzina INTERIOR
 * i dwa różne fachy — ekstraktor i chemia kontra igła, skóra i barwnik. Inny czas,
 * inne stanowisko, inny rząd cen. To dokładnie ten defekt, którym sekcja „Podobnych
 * zleceń" podpowiedziała mycie detailingowe do zapytania o renowację fotela.
 *
 * OPERACJA odpowiada na pytanie „co się robi", CZĘŚĆ — „na czym". Obie są
 * klasyfikowane z SAMEJ NAZWY usługi (jak rodzina i zakres), raz na nazwę w skali
 * świata, więc ich koszt w czasie życia systemu jest pomijalny.
 */
enum class ServiceOperation {
    /** Mycie, czyszczenie, pranie — usuwanie brudu bez ingerencji w materiał. */
    CLEAN,

    /** Powłoki i woski — nakładanie warstwy ochronnej (bez folii). */
    PROTECT,

    /** Korekta lakieru, polerowanie — zdejmowanie defektów mechanicznie. */
    CORRECT,

    /** Naprawa i renowacja substancji: tapicerka, skóra, plastik. Igła i barwnik, nie ekstraktor. */
    REPAIR,

    /** Oklejanie folią — PPF i wrap; rodzina rozstrzyga, która folia. */
    APPLY_FILM,

    /** Przyciemnianie szyb i lamp. */
    TINT,

    /** Demontaż i usuwanie: starej folii, kleju, elementów. */
    REMOVE,

    /** Montaż elementów i akcesoriów. */
    MOUNT,

    /** Ozonowanie, dezynfekcja, usuwanie zapachów. */
    SANITIZE,

    /** Oględziny, inspekcja, przegląd stanu. */
    INSPECT,

    /** Robota spoza powyższych, ale nazwana (transport, dorabianie kluczyka). */
    OTHER_OP,

    /** Nazwa nie mówi, co się robi. */
    UNKNOWN;

    companion object {
        fun from(raw: String?): ServiceOperation =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Część auta, której robota dotyczy — wyczytana z samej nazwy usługi. */
enum class ServicePart {
    FULL_BODY,
    BODY_FRONT,
    BODY_PANEL,
    TRIM_PIECE,
    LAMPS,
    GLASS,
    WHEELS,
    ENGINE_BAY,
    CABIN,
    SEAT,
    DOOR_PANEL,
    DASHBOARD,
    HEADLINER,
    CARPET,
    UNKNOWN;

    companion object {
        fun from(raw: String?): ServicePart =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * Macierze zgodności osi — JAWNE TABLICE zamiast progu na liczbach.
 *
 * Dzisiejsze MIN_COVERAGE=0.5 jest równie arbitralne co te tablice, ale nietestowalne:
 * nie da się napisać testu „czyszczenie nie dopasowuje się do naprawy", bo próg nie zna
 * pojęcia rzemiosła. Tu każda komórka jest zdaniem po polsku i osobnym testem.
 *
 * Start jest MAKSYMALNIE WĄSKI (tożsamość + jawnie wypisane pary): wysoka precyzja,
 * niskie pokrycie, rozluźnianie dopiero na danych z dziennika decyzji. Rozszerzenie
 * macierzy to dopisanie pary TUTAJ i testu obok — nigdy „poprawka promptu".
 */
object WorkAxisCompatibility {

    /**
     * Pary operacji, które wolno porównywać cenowo POZA tożsamością.
     * Symetryczne — kolejność w parze nie ma znaczenia.
     */
    private val compatibleOperationPairs: Set<Set<ServiceOperation>> = setOf(
        // Ozonowanie sprzedaje się osobno i w pakiecie z czyszczeniem wnętrza —
        // to samo stanowisko, ta sama rozmowa o cenie.
        setOf(ServiceOperation.CLEAN, ServiceOperation.SANITIZE)
    )

    /**
     * Grupy części WYMIENNYCH CENOWO w obrębie jednej operacji.
     *
     * REPAIR: tapicerka to jedno rzemiosło niezależnie od tego, czy łata się fotel,
     * boczek czy podsufitkę — comp z innej części kabiny jest uczciwą kotwicą,
     * a różnicę części nazywa jawnie karta („fotel vs boczek drzwi").
     *
     * APPLY_FILM: drobne elementy (listwa, próg, lampa) są między sobą porównywalne;
     * full body, przód i pojedynczy panel to osobne światy cenowe — dokładnie ta
     * różnica, której zabrakło przy PPF za 850 zł kontra 18 450 zł.
     *
     * CLEAN/SANITIZE: czyszczenie dowolnej części kabiny to jeden fach; o różnicy
     * skali rozstrzyga bramka cenowa, nie część.
     */
    private val interiorParts = setOf(
        ServicePart.CABIN, ServicePart.SEAT, ServicePart.DOOR_PANEL,
        ServicePart.DASHBOARD, ServicePart.HEADLINER, ServicePart.CARPET
    )

    private val partGroups: Map<ServiceOperation, List<Set<ServicePart>>> = mapOf(
        ServiceOperation.REPAIR to listOf(interiorParts),
        ServiceOperation.CLEAN to listOf(interiorParts),
        ServiceOperation.SANITIZE to listOf(interiorParts),
        ServiceOperation.APPLY_FILM to listOf(setOf(ServicePart.TRIM_PIECE, ServicePart.LAMPS))
    )

    /**
     * Czy operację kandydata wolno porównać z operacją potrzeby.
     * UNKNOWN po KTÓREJKOLWIEK stronie = brak danych → bramka POMIJANA (true),
     * nigdy zgadywana: o odrzuceniu może wtedy zdecydować wyłącznie inna bramka.
     */
    fun operationsComparable(need: ServiceOperation, candidate: ServiceOperation): Boolean {
        if (need == ServiceOperation.UNKNOWN || candidate == ServiceOperation.UNKNOWN) return true
        if (need == candidate) return true
        return compatibleOperationPairs.any { pair -> need in pair && candidate in pair }
    }

    /**
     * Czy część kandydata wolno porównać z częścią potrzeby przy danej operacji.
     * UNKNOWN po którejkolwiek stronie = brak danych → bramka pomijana.
     */
    fun partsComparable(operation: ServiceOperation, need: ServicePart, candidate: ServicePart): Boolean {
        if (need == ServicePart.UNKNOWN || candidate == ServicePart.UNKNOWN) return true
        if (need == candidate) return true
        return partGroups[operation].orEmpty().any { group -> need in group && candidate in group }
    }
}
