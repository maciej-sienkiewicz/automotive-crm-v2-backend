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
    DEVICE_DRAWN,
    SAVED_SIGNATURE
}

/** Na jakiej podstawie rozpatrujący działa w imieniu pracodawcy (drukowane na dokumencie). */
enum class ApprovalBasis {
    OWNER,
    PERMISSION
}

/** Skąd wniosek przyszedł. W tym wydaniu wyłącznie samoobsługa pracownika. */
enum class LeaveRequestOrigin {
    SELF_SERVICE
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
