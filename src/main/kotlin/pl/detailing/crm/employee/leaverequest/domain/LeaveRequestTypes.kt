package pl.detailing.crm.employee.leaverequest.domain

import pl.detailing.crm.employee.leave.domain.LeaveType

/**
 * Cykl życia wniosku urlopowego (projekt PRJ/2026/09/01, §2.3).
 *
 * DRAFT nie jest dokumentem: szkic bez podpisu pracownika nie trafia do kolejki ani do
 * kalendarza, a job usuwa go po dobie. Dokumentem staje się dopiero PENDING.
 */
enum class LeaveRequestStatus {
    /** Utworzony i wypełniony PDF (H1), czeka na podpis pracownika. */
    DRAFT,

    /** Podpisany przez pracownika (H2), czeka na decyzję. */
    PENDING,

    /** Zatwierdzony i podpisany przez rozpatrującego (H3); istnieje wpis w employee_leaves. */
    APPROVED,

    /** Odrzucony — odmowa też jest decyzją pracodawcy na dokumencie, więc też podpisana. */
    REJECTED,

    /** Wycofany przez pracownika przed decyzją. */
    WITHDRAWN,

    /** Zatwierdzony i odwołany przed rozpoczęciem urlopu; wpis w employee_leaves usunięty. */
    CANCELLED,

    /** Termin rozpoczęcia minął bez decyzji. */
    EXPIRED;

    companion object {
        /** „Rozpatrzone" w kolejce rozpatrujących — wszystko, co już nie czeka. */
        val DECIDED: Set<LeaveRequestStatus> = setOf(APPROVED, REJECTED, CANCELLED, EXPIRED, WITHDRAWN)

        /** Wnioski, które zajmują termin pracownika — z nimi nowy wniosek nie może się nakładać. */
        val BLOCKING: Set<LeaveRequestStatus> = setOf(PENDING, APPROVED)
    }
}

/** Jak złożono podpis. Pracownik zawsze rysuje na żywo — to jego oświadczenie woli. */
enum class LeaveSignatureMethod {
    /** Narysowany na ekranie własnego urządzenia podpisującego (samoobsługa, decyzja). */
    DEVICE_DRAWN,

    /** Zapisany podpis z profilu rozpatrującego, użyty świadomie przy decyzji. */
    SAVED_SIGNATURE,

    /**
     * Pracownik podpisał osobiście, na ekranie urządzenia osoby, która wprowadziła wniosek
     * w jego imieniu (ON_BEHALF). Nadal narysowany na żywo — inna jest tylko sesja: konto
     * i urządzenie należą do wprowadzającego, więc karta podpisów musi to powiedzieć wprost.
     */
    IN_PERSON
}

/**
 * Na jakiej podstawie rozpatrujący działa w imieniu pracodawcy. Zapisywana w bazie
 * i w dzienniku zdarzeń jako ślad decyzji; od v2 kontraktu nie jest drukowana na
 * dokumencie ani zwracana w API.
 */
enum class ApprovalBasis {
    OWNER,
    PERMISSION
}

/** Skąd wniosek przyszedł. */
enum class LeaveRequestOrigin {
    /** Pracownik złożył sam, w zakładce „Urlop". */
    SELF_SERVICE,

    /**
     * Wprowadził administrator (właściciel albo osoba z EMPLOYEES_LEAVES_APPROVE), a pracownik
     * podpisał osobiście na jego urządzeniu. Pracownik nie musi mieć konta w systemie.
     * Szkic należy do wprowadzającego (`created_by`): tylko on widzi go i porzuca, a pracownik
     * — nawet z kontem — nie podpisze go z własnej samoobsługi, bo dokument mówi „podpisany
     * osobiście na urządzeniu wprowadzającego".
     */
    ON_BEHALF
}

/**
 * Rodzaje urlopu, o które można WNIOSKOWAĆ. Zwolnienie lekarskie (SICK) nie jest
 * wnioskiem — pochodzi z e-ZLA, a nie z prośby pracownika, i przełożony wpisuje je
 * bezpośrednio w nieobecnościach.
 */
object RequestableLeaveTypes {
    val ALL: Set<LeaveType> = setOf(LeaveType.ANNUAL, LeaveType.UNPAID, LeaveType.SPECIAL, LeaveType.PARENTAL, LeaveType.CARE)

    /** Nazwa rodzaju urlopu do zdań i dokumentów — „urlop wypoczynkowy", „urlop na żądanie". */
    fun label(type: LeaveType, onDemand: Boolean): String = when {
        type == LeaveType.ANNUAL && onDemand -> "urlop na żądanie"
        type == LeaveType.ANNUAL -> "urlop wypoczynkowy"
        type == LeaveType.UNPAID -> "urlop bezpłatny"
        type == LeaveType.SPECIAL -> "urlop okolicznościowy"
        type == LeaveType.PARENTAL -> "urlop rodzicielski / wychowawczy"
        type == LeaveType.CARE -> "opieka nad dzieckiem"
        else -> "zwolnienie lekarskie"
    }
}
