package pl.detailing.crm.worktime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.worktime.attendance.AttendanceSheetEntity
import pl.detailing.crm.worktime.attendance.AttendanceSheetStatus
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Lista miesięczna: karty zbierane → zatwierdzane → lista obecności podpisana.
 * Jeden przepływ na miesiąc, w którym lista powstaje wyłącznie z zatwierdzonych kart,
 * a zmiana karty po podpisie unieważnia listę.
 */
class WorkTimeMonthFlowTest {

    private val kit = WorkTimeTestKit(today = LocalDate.of(2026, 9, 15))
    private val studio = kit.studio
    private val september = YearMonth.of(2026, 9)
    private val manager = kit.user("Marek", "Menedżer", tracked = false)
    private val managerId = kit.id(manager)

    private fun sheet(
        status: AttendanceSheetStatus,
        userIds: List<UUID>,
        createdAt: Instant = Instant.parse("2026-10-01T08:00:00Z"),
        outdatedAt: Instant? = null
    ) = AttendanceSheetEntity(
        id = UUID.randomUUID(), studioId = studio.value, period = september.toString(), employeeIdsJson = "[]",
        fileS3Key = "k.pdf", createdBy = UUID.randomUUID(), createdAt = createdAt, status = status,
        outdatedAt = outdatedAt, userIdsJson = userIds.joinToString(",", "[", "]") { "\"$it\"" }
    )

    // ── Etap miesiąca ────────────────────────────────────────────────────────

    @Nested
    inner class Stage {
        private fun stage(vararg statuses: CardStatus, signed: Boolean? = null, outdated: Boolean = false) =
            WorkTimeMonthService.stageOf(statuses.toList(), signed, outdated)

        @Test
        fun `COLLECTING gdy sa karty niezlozone i zadna nie czeka na decyzje`() {
            assertEquals(MonthStage.COLLECTING, stage(CardStatus.NOT_STARTED, CardStatus.APPROVED))
            assertEquals(MonthStage.COLLECTING, stage(CardStatus.DRAFT, CardStatus.RETURNED))
            assertEquals(MonthStage.COLLECTING, stage(), "Miesiąc bez kart nie jest gotowy do podpisu")
        }

        @Test
        fun `REVIEWING gdy choc jedna karta czeka na decyzje - takze nad podpisana lista`() {
            assertEquals(MonthStage.REVIEWING, stage(CardStatus.SUBMITTED, CardStatus.NOT_STARTED))
            assertEquals(MonthStage.REVIEWING, stage(CardStatus.SUBMITTED, CardStatus.APPROVED, signed = true, outdated = true))
        }

        @Test
        fun `READY_TO_SIGN gdy wszystkie zatwierdzone i nie ma waznej podpisanej listy`() {
            assertEquals(MonthStage.READY_TO_SIGN, stage(CardStatus.APPROVED, CardStatus.APPROVED))
            assertEquals(MonthStage.READY_TO_SIGN, stage(CardStatus.APPROVED, signed = false), "Lista wygenerowana, niepodpisana")
        }

        @Test
        fun `SIGNED gdy najnowsza lista jest podpisana i aktualna - takze ze swiadomie pominietymi`() {
            assertEquals(MonthStage.SIGNED, stage(CardStatus.APPROVED, signed = true))
            assertEquals(MonthStage.SIGNED, stage(CardStatus.APPROVED, CardStatus.NOT_STARTED, signed = true))
        }

        @Test
        fun `NEEDS_RESIGN gdy podpisana lista jest nieaktualna`() {
            assertEquals(MonthStage.NEEDS_RESIGN, stage(CardStatus.APPROVED, signed = true, outdated = true))
            assertEquals(MonthStage.NEEDS_RESIGN, stage(CardStatus.RETURNED, signed = true, outdated = true))
        }
    }

    @Test
    fun `przeglad miesiaca - osoby po nazwisku, liczniki, etap i lista`() {
        val zofia = kit.user("Zofia", "Zawada")
        val adam = kit.user("Adam", "Abacki")
        val ewa = kit.user("Ewa", "Ewska")
        val notTracked = kit.user("Nie", "Liczy", tracked = false)
        kit.period(adam, september, PeriodStatus.SUBMITTED)
        kit.period(ewa, september, PeriodStatus.APPROVED).apply { approvedAt = Instant.now(); approvedBy = manager.id }
        kit.entry(zofia, september.atDay(1))
        kit.period(zofia, september, PeriodStatus.DRAFT)
        val signed = sheet(AttendanceSheetStatus.APPROVED, listOf(ewa.id), createdAt = Instant.parse("2026-09-10T08:00:00Z"))
        val newest = sheet(AttendanceSheetStatus.GENERATED, listOf(ewa.id))
        io.mockk.every { kit.sheets.findByStudioIdAndPeriod(studio.value, "2026-09") } returns listOf(newest, signed)

        val overview = kit.monthService.overview(studio, managerId, september)

        assertEquals(listOf("Abacki", "Ewska", "Zawada"), overview.employees.map { it.name.substringAfter(" ") })
        assertFalse(overview.employees.any { it.userId == notTracked.id.toString() })
        assertEquals(MonthCountsResponse(total = 3, notSubmitted = 1, submitted = 1, returned = 0, approved = 1), overview.counts)
        assertEquals(MonthStage.REVIEWING, overview.stage)
        assertEquals(22, overview.workingDays)
        assertEquals("Wrzesień 2026", overview.label)
        assertEquals(newest.id.toString(), overview.sheet?.id)
        assertEquals(listOf(signed.id.toString()), overview.sheetHistory.map { it.id })

        val ewaRow = overview.employees.single { it.userId == ewa.id.toString() }
        assertEquals("Marek Menedżer", ewaRow.approvedByName)
        assertTrue(ewaRow.canDecide, "Zatwierdzoną kartę można odblokować")
        assertEquals(kit.employeeOf(ewa).id.toString(), ewaRow.employeeId)
        assertEquals(22 * 480, ewaRow.expectedMinutes)
        val zofiaRow = overview.employees.single { it.userId == zofia.id.toString() }
        assertEquals(CardStatus.DRAFT, zofiaRow.status)
        assertFalse(zofiaRow.canDecide, "Niezłożonej karty nie ma o czym decydować")
        // Do 15.09 jest 11 dni roboczych, jeden ma wpis.
        assertEquals(10, zofiaRow.missingWorkingDays)
    }

    @Test
    fun `wlasna karta menedzera jest na liscie, ale bez prawa decyzji`() {
        val trackedManager = kit.user("Maria", "Kierowniczka")
        kit.period(trackedManager, september, PeriodStatus.SUBMITTED)

        val row = kit.monthService.overview(studio, kit.id(trackedManager), september).employees.single()

        assertEquals(CardStatus.SUBMITTED, row.status)
        assertFalse(row.canDecide)
    }

    @Test
    fun `osoba bez roli liczacej czas, ale z karta za ten miesiac, nadal jest na liscie`() {
        val former = kit.user("Były", "Pracownik", tracked = false)
        kit.period(former, september, PeriodStatus.APPROVED)
        val joinedLater = kit.user("Nowy", "Pracownik", createdAt = Instant.parse("2026-10-05T08:00:00Z"))

        val ids = kit.monthService.overview(studio, managerId, september).employees.map { it.userId }

        assertTrue(former.id.toString() in ids)
        assertFalse(joinedLater.id.toString() in ids, "Konto założone po miesiącu nie ma za niego karty")
    }

    @Test
    fun `karta jednej osoby - wszystkie dni miesiaca, swieta, urlop i zwrot`() {
        val anna = kit.user("Anna", "Nowak")
        val november = YearMonth.of(2026, 11)
        kit.leave(anna, november.atDay(2), november.atDay(3), LeaveType.SICK)
        kit.entry(anna, november.atDay(4))
        kit.period(anna, november, PeriodStatus.RETURNED).apply {
            returnNote = "Brak 5.11"; returnedAt = Instant.parse("2026-12-01T09:00:00Z"); returnedBy = manager.id
        }

        val card = kit.monthService.cardDetail(studio, managerId, november, kit.id(anna))

        assertEquals(30, card.days.size)
        assertEquals(CardLeaveResponse("SICK", "L4"), card.days.single { it.date == "2026-11-02" }.leave)
        assertEquals("Narodowe Święto Niepodległości", card.days.single { it.date == "2026-11-11" }.holidayName)
        assertEquals(480, card.days.single { it.date == "2026-11-04" }.minutes)
        assertEquals((20 - 2) * 480, card.expectedMinutes)
        assertEquals(2, card.leaveWorkingDays)
        assertEquals("Brak 5.11", card.returnNote)
        assertEquals("Marek Menedżer", card.returnedByName)
        assertEquals(CardStatus.RETURNED, card.status)
    }

    // ── Decyzje ──────────────────────────────────────────────────────────────

    @Test
    fun `zatwierdzenie karty zwroconej do poprawy to 409 - najpierw pracownik musi ja zlozyc`() {
        val anna = kit.user("Anna", "Nowak")
        val period = kit.period(anna, september, PeriodStatus.RETURNED)

        val error = assertThrows<ConflictException> {
            kit.monthService.approveCard(studio, managerId, "Marek Menedżer", september, kit.id(anna))
        }

        assertTrue("zwrócona do poprawy" in error.message!!, error.message)
        assertEquals(PeriodStatus.RETURNED, period.status)
        assertTrue(kit.events.isEmpty())
    }

    @Test
    fun `zatwierdzenie czysci notatke zwrotu, zwraca wiersz i powiadamia pracownika`() {
        val anna = kit.user("Anna", "Nowak")
        kit.period(anna, september, PeriodStatus.SUBMITTED).apply { returnNote = "Popraw 3.09" }

        val row = kit.monthService.approveCard(studio, managerId, "Marek Menedżer", september, kit.id(anna))

        assertEquals(CardStatus.APPROVED, row.status)
        assertEquals("Marek Menedżer", row.approvedByName)
        assertNull(kit.periodOf(anna, september)!!.returnNote)
        val event = kit.events.filterIsInstance<WorkTimeCardDecidedEvent>().single()
        assertEquals(WorkTimeCardDecidedEvent.Outcome.APPROVED, event.outcome)
        // Lista podpisana bez tej osoby przestaje być pełna.
        verify {
            kit.sheetService.outdateAfterCardChange(
                studio, september, kit.id(anna), EmployeeId(kit.employeeOf(anna).id), false, managerId, "Marek Menedżer"
            )
        }
    }

    @Test
    fun `zwrot wymaga notatki`() {
        val anna = kit.user("Anna", "Nowak")
        val period = kit.period(anna, september, PeriodStatus.SUBMITTED)

        listOf(null, "", "   ").forEach { note ->
            val error = assertThrows<ValidationException> {
                kit.monthService.returnCard(studio, managerId, "Marek", september, kit.id(anna), note)
            }
            assertEquals("note", error.field)
        }
        assertThrows<ValidationException> {
            kit.monthService.returnCard(studio, managerId, "Marek", september, kit.id(anna), "x".repeat(1001))
        }
        assertEquals(PeriodStatus.SUBMITTED, period.status)
    }

    @Test
    fun `odblokowanie zatwierdzonej karty uniewaznia liste, na ktorej jest ta osoba`() {
        val anna = kit.user("Anna", "Nowak")
        kit.period(anna, september, PeriodStatus.APPROVED)

        val row = kit.monthService.returnCard(studio, managerId, "Marek", september, kit.id(anna), "  Brak 3.09  ")

        assertEquals(CardStatus.RETURNED, row.status)
        assertEquals("Brak 3.09", row.returnNote)
        verify {
            kit.sheetService.outdateAfterCardChange(
                studio, september, kit.id(anna), EmployeeId(kit.employeeOf(anna).id), true, managerId, "Marek"
            )
        }
        val event = kit.events.filterIsInstance<WorkTimeCardDecidedEvent>().single()
        assertEquals(WorkTimeCardDecidedEvent.Outcome.RETURNED, event.outcome)
        assertEquals("Brak 3.09", event.note)
    }

    @Test
    fun `zwrot zlozonej karty nie rusza listy obecnosci`() {
        val anna = kit.user("Anna", "Nowak")
        kit.period(anna, september, PeriodStatus.SUBMITTED)

        kit.monthService.returnCard(studio, managerId, "Marek", september, kit.id(anna), "Popraw")

        verify(exactly = 0) { kit.sheetService.outdateAfterCardChange(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `zwrot karty juz zwroconej albo szkicu to 409`() {
        val anna = kit.user("Anna", "Nowak")
        val bob = kit.user("Bob", "Budowlany")
        kit.period(anna, september, PeriodStatus.RETURNED)
        kit.period(bob, september, PeriodStatus.DRAFT)

        assertThrows<ConflictException> { kit.monthService.returnCard(studio, managerId, "M", september, kit.id(anna), "x") }
        assertThrows<ConflictException> { kit.monthService.returnCard(studio, managerId, "M", september, kit.id(bob), "x") }
    }

    @Test
    fun `zbiorcze zatwierdzenie pomija wlasna karte i niezlozone`() {
        val me = kit.user("Maria", "Kierowniczka")
        val anna = kit.user("Anna", "Nowak")
        val bob = kit.user("Bob", "Budowlany")
        val cyryl = kit.user("Cyryl", "Cichy")
        kit.period(me, september, PeriodStatus.SUBMITTED)
        kit.period(anna, september, PeriodStatus.SUBMITTED)
        kit.period(bob, september, PeriodStatus.DRAFT)
        kit.period(cyryl, september, PeriodStatus.RETURNED)

        val result = kit.monthService.bulkApprove(
            studio, kit.id(me), "Maria", september,
            listOf(me.id, anna.id, bob.id, cyryl.id).map { it.toString() } + "nie-uuid"
        )

        assertEquals(listOf(anna.id.toString()), result.approved)
        assertEquals(
            listOf(me.id.toString(), bob.id.toString(), cyryl.id.toString(), "nie-uuid"),
            result.skipped.map { it.userId }
        )
        assertTrue(result.skipped.first().reason.contains("czterech oczu"))
        assertEquals(PeriodStatus.SUBMITTED, kit.periodOf(me, september)!!.status)
        assertEquals(PeriodStatus.APPROVED, kit.periodOf(anna, september)!!.status)
        assertEquals(PeriodStatus.DRAFT, kit.periodOf(bob, september)!!.status)
    }

    @Test
    fun `przypomnienie najwyzej raz na 12 godzin, tylko o kartach niezlozonych`() {
        val anna = kit.user("Anna", "Nowak")
        val bob = kit.user("Bob", "Budowlany")
        kit.period(bob, september, PeriodStatus.SUBMITTED)
        val ids = listOf(anna.id.toString(), bob.id.toString())

        val first = kit.monthService.remind(studio, managerId, september, ids)
        val second = kit.monthService.remind(studio, managerId, september, ids)

        assertEquals(listOf(anna.id.toString()), first.reminded)
        assertEquals(listOf(bob.id.toString()), first.skipped.map { it.userId })
        assertTrue(second.reminded.isEmpty())
        assertTrue(second.skipped.single { it.userId == anna.id.toString() }.reason.contains("12 godzin"))
        assertEquals(1, kit.events.filterIsInstance<WorkTimeCardReminderEvent>().size)

        // Przypomnienie nie „zaczyna" karty za pracownika.
        val row = kit.monthService.overview(studio, managerId, september).employees.single { it.userId == anna.id.toString() }
        assertEquals(CardStatus.NOT_STARTED, row.status)
        assertNotNull(row.remindedAt)

        // Po 12 godzinach znowu wolno (zegar testu: 15.09.2026, 12:00 w Warszawie).
        kit.periodOf(anna, september)!!.remindedAt = Instant.parse("2026-09-15T10:00:00Z").minus(13, ChronoUnit.HOURS)
        assertEquals(listOf(anna.id.toString()), kit.monthService.remind(studio, managerId, september, ids).reminded)
    }

    @Test
    fun `licznik czekajacych - karty do decyzji bez wlasnej i miesiace do podpisu`() {
        val me = kit.user("Maria", "Kierowniczka")
        val anna = kit.user("Anna", "Nowak")
        val august = YearMonth.of(2026, 8)
        kit.period(me, september, PeriodStatus.SUBMITTED)
        kit.period(anna, september, PeriodStatus.SUBMITTED)
        // Sierpień: obie karty zatwierdzone, listy brak → do podpisu.
        kit.period(me, august, PeriodStatus.APPROVED)
        kit.period(anna, august, PeriodStatus.APPROVED)

        val count = kit.monthService.pendingCount(studio, kit.id(me))

        assertEquals(1, count.submittedCards)
        assertEquals(1, count.sheetsToSign)
    }

    // ── Lista obecności ──────────────────────────────────────────────────────

    @Test
    fun `lista z niezatwierdzonymi kartami bez zgody to 409 z nazwiskami`() {
        val anna = kit.user("Anna", "Nowak")
        kit.user("Bob", "Budowlany")
        kit.period(anna, september, PeriodStatus.APPROVED)

        val error = assertThrows<IncompleteAttendanceSheetException> {
            runBlocking { kit.monthService.generateSheet(studio, managerId, "Marek", september, allowIncomplete = false) }
        }

        assertEquals(listOf("Bob Budowlany"), error.names)
        coVerify(exactly = 0) { kit.sheetService.generateFromCards(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `lista za zgoda pomija niezatwierdzonych, wypisuje ich i zastepuje niepodpisana liste`() {
        val anna = kit.user("Anna", "Nowak")
        kit.user("Bob", "Budowlany")
        kit.period(anna, september, PeriodStatus.APPROVED)
        val oldUnsigned = sheet(AttendanceSheetStatus.GENERATED, listOf(anna.id))
        val oldSigned = sheet(AttendanceSheetStatus.APPROVED, listOf(anna.id), createdAt = Instant.parse("2026-09-01T08:00:00Z"))
        io.mockk.every { kit.sheets.findByStudioIdAndPeriod(studio.value, "2026-09") } returns listOf(oldUnsigned, oldSigned)
        val created = sheet(AttendanceSheetStatus.GENERATED, listOf(anna.id))
        coEvery { kit.sheetService.generateFromCards(any(), any(), any(), any(), any(), any(), any()) } returns created
        io.mockk.every { kit.sheetService.excludedNamesOf(created) } returns listOf("Bob Budowlany")

        val result = runBlocking { kit.monthService.generateSheet(studio, managerId, "Marek", september, allowIncomplete = true) }

        coVerify {
            kit.sheetService.generateFromCards(
                studio, managerId, "Marek", september,
                listOf(UserId(anna.id)), listOf(EmployeeId(kit.employeeOf(anna).id)), listOf("Bob Budowlany")
            )
        }
        coVerify { kit.sheetService.deleteIfUnsigned(studio, managerId, "Marek", oldUnsigned.id) }
        coVerify(exactly = 0) { kit.sheetService.deleteIfUnsigned(any(), any(), any(), oldSigned.id) }
        assertEquals(created.id.toString(), result.id)
        assertEquals(listOf("Bob Budowlany"), result.excludedNames)
    }

    @Test
    fun `lista bez zadnej zatwierdzonej karty to 409`() {
        kit.user("Anna", "Nowak")

        assertThrows<ConflictException> {
            runBlocking { kit.monthService.generateSheet(studio, managerId, "Marek", september, allowIncomplete = true) }
        }
    }

    // ── Pracownik ────────────────────────────────────────────────────────────

    @Nested
    inner class EmployeeSide {
        private val anna = kit.user("Anna", "Nowak")
        private val annaId = kit.id(anna)

        @Test
        fun `zlozona karta jest tylko do odczytu - wszystkie cztery zmiany to 409`() {
            kit.period(anna, september, PeriodStatus.SUBMITTED)
            kit.entry(anna, september.atDay(1))
            val expected = "Karta za wrzesień 2026 czeka na decyzję przełożonego. Jeśli trzeba coś poprawić, poproś o zwrot."

            val attempts = listOf<() -> Unit>(
                { kit.service.upsertEntry(annaId, studio, september.atDay(2), 480, null) },
                { kit.service.deleteEntry(annaId, studio, september.atDay(1)) },
                { kit.service.fillMonth(annaId, studio, september) },
                { kit.service.standardToday(annaId, studio) }
            )
            attempts.forEach { attempt ->
                val error = assertThrows<ConflictException> { attempt() }
                assertEquals(expected, error.message)
            }
            assertEquals(1, kit.entriesOf(anna).size)
            verify(exactly = 0) { kit.entries.delete(any()) }
        }

        @Test
        fun `zatwierdzona karta nadal jest zablokowana do odblokowania`() {
            kit.period(anna, september, PeriodStatus.APPROVED)

            assertThrows<ForbiddenException> { kit.service.upsertEntry(annaId, studio, september.atDay(2), 480, null) }
        }

        @Test
        fun `uzupelnij miesiac pomija swieta i dni urlopu`() {
            val november = YearMonth.of(2026, 11)
            kit.leave(anna, november.atDay(2), november.atDay(6))
            kit.entry(anna, november.atDay(9), 300)

            kit.service.fillMonth(annaId, studio, november)

            val days = kit.entriesOf(anna).map { it.date }
            // 20 dni roboczych − 5 dni urlopu − 1 dzień z własnym wpisem = 14 nowych + 1 istniejący.
            assertEquals(15, days.size)
            assertFalse(november.atDay(11) in days, "Święto Niepodległości")
            assertTrue((2..6).none { november.atDay(it) in days }, "Urlop")
            assertTrue(days.none { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY })
            assertEquals(300, kit.entriesOf(anna).single { it.date == november.atDay(9) }.minutes, "Własny wpis zostaje")
        }

        @Test
        fun `karta pracownika ma dni, norme, braki i notatke tylko przy zwrocie`() {
            kit.entry(anna, september.atDay(1))
            kit.period(anna, september, PeriodStatus.SUBMITTED).apply { returnNote = "stara notatka" }

            val detail = kit.service.getPeriodDetail(annaId, studio, september)

            assertEquals(30, detail.days.size)
            assertEquals(22 * 480, detail.expectedMinutes)
            assertEquals(10, detail.missingWorkingDays)
            assertNull(detail.returnNote, "Notatka dotyczyła poprzedniej wersji karty")

            kit.periodOf(anna, september)!!.status = PeriodStatus.RETURNED
            assertEquals("stara notatka", kit.service.getPeriodDetail(annaId, studio, september).returnNote)
        }

        @Test
        fun `ponowne zlozenie po zwrocie zapisuje w dzienniku RETURNED na SUBMITTED i powiadamia menedzerow`() {
            kit.period(anna, september, PeriodStatus.RETURNED)

            kit.service.submitPeriod(annaId, studio, september)

            val change = kit.audited.single { it.action == AuditAction.WORK_TIME_PERIOD_SAVED }.changes.single { it.field == "status" }
            assertEquals("RETURNED", change.oldValue)
            assertEquals("SUBMITTED", change.newValue)
            val event = kit.events.filterIsInstance<WorkTimeCardSubmittedEvent>().single()
            assertEquals(annaId, event.employeeUserId)
            assertEquals("Anna Nowak", event.employeeName)
        }
    }
}
