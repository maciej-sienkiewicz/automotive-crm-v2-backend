package pl.detailing.crm.appointment

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.appointment.cancel.CancelAppointmentHandler
import pl.detailing.crm.appointment.create.CreateAppointmentHandler
import pl.detailing.crm.appointment.create.CreateAppointmentResult
import pl.detailing.crm.appointment.delete.DeleteAppointmentHandler
import pl.detailing.crm.appointment.get.GetAppointmentHandler
import pl.detailing.crm.appointment.list.ListAppointmentsHandler
import pl.detailing.crm.appointment.recurrence.create.CreateRecurringAppointmentHandler
import pl.detailing.crm.appointment.recurrence.delete.DeleteRecurringAppointmentHandler
import pl.detailing.crm.appointment.recurrence.get.GetRecurrenceSeriesHandler
import pl.detailing.crm.appointment.recurrence.update.UpdateRecurringAppointmentCommand
import pl.detailing.crm.appointment.recurrence.update.UpdateRecurringAppointmentHandler
import pl.detailing.crm.appointment.recurrence.update.UpdateRecurringAppointmentResult
import pl.detailing.crm.appointment.restore.RestoreAppointmentHandler
import pl.detailing.crm.appointment.smsprefs.UpdateAppointmentSmsPreferencesHandler
import pl.detailing.crm.appointment.title.UpdateAppointmentTitleHandler
import pl.detailing.crm.appointment.update.UpdateAppointmentHandler
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.shared.*
import pl.detailing.crm.smscampaigns.bookingconfirmation.SendBookingConfirmationSmsHandler
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.util.UUID

/**
 * Standalone MockMvc E2E test for PUT /api/v1/appointments/{id}?scope=<SCOPE>.
 *
 * Verifies that the scope parameter correctly routes to updateRecurringAppointmentHandler
 * for scope=ALL and scope=THIS_AND_FUTURE, while scope=THIS (or absent scope)
 * only calls the single-appointment updateAppointmentHandler.
 */
class UpdateAppointmentRecurrenceScopeE2ETest {

    private val updateAppointmentHandler = mockk<UpdateAppointmentHandler>()
    private val updateRecurringAppointmentHandler = mockk<UpdateRecurringAppointmentHandler>()

    private lateinit var mockMvc: MockMvc

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val appointmentId = AppointmentId.random()

    private val defaultUpdateResult = CreateAppointmentResult(
        appointmentId = appointmentId,
        customerId = CustomerId.random(),
        vehicleId = null,
        totalNet = Money(30000L),
        totalGross = Money(30000L),
        totalVat = Money(0L)
    )

    private val recurringResult = UpdateRecurringAppointmentResult(
        updatedCount = 3,
        skippedDetachedCount = 0,
        skippedConvertedCount = 0
    )

    @BeforeEach
    fun setUp() {
        val controller = AppointmentController(
            createAppointmentHandler = mockk(relaxed = true),
            updateAppointmentHandler = updateAppointmentHandler,
            cancelAppointmentHandler = mockk(relaxed = true),
            restoreAppointmentHandler = mockk(relaxed = true),
            deleteAppointmentHandler = mockk(relaxed = true),
            listAppointmentsHandler = mockk(relaxed = true),
            getAppointmentHandler = mockk(relaxed = true),
            updateAppointmentTitleHandler = mockk(relaxed = true),
            sendBookingConfirmationSmsHandler = mockk(relaxed = true),
            studioRepository = mockk(relaxed = true),
            updateAppointmentSmsPreferencesHandler = mockk(relaxed = true),
            createRecurringAppointmentHandler = mockk(relaxed = true),
            updateRecurringAppointmentHandler = updateRecurringAppointmentHandler,
            deleteRecurringAppointmentHandler = mockk(relaxed = true),
            getRecurrenceSeriesHandler = mockk(relaxed = true)
        )

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler(mockk(relaxed = true)))
            .build()

        givenAuthenticatedOwner()

        coEvery { updateAppointmentHandler.handle(any()) } returns defaultUpdateResult
        coEvery { updateRecurringAppointmentHandler.handle(any()) } returns recurringResult
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ─── scope=ALL ────────────────────────────────────────────────────────────

    @Test
    fun `scope ALL returns 200 OK`() {
        mockMvc.perform(updateRequest(scope = "ALL"))
            .andExpect(status().isOk)
    }

    @Test
    fun `scope ALL calls updateRecurringAppointmentHandler`() {
        mockMvc.perform(updateRequest(scope = "ALL"))
            .andExpect(status().isOk)

        coVerify(exactly = 1) { updateRecurringAppointmentHandler.handle(any()) }
    }

    @Test
    fun `scope ALL calls updateAppointmentHandler for anchor`() {
        mockMvc.perform(updateRequest(scope = "ALL"))
            .andExpect(status().isOk)

        coVerify(exactly = 1) { updateAppointmentHandler.handle(any()) }
    }

    @Test
    fun `scope ALL passes ALL to updateRecurringAppointmentHandler`() {
        val cmdSlot = slot<UpdateRecurringAppointmentCommand>()
        coEvery { updateRecurringAppointmentHandler.handle(capture(cmdSlot)) } returns recurringResult

        mockMvc.perform(updateRequest(scope = "ALL"))
            .andExpect(status().isOk)

        assertEquals(pl.detailing.crm.appointment.recurrence.update.RecurrenceEditScope.ALL, cmdSlot.captured.scope)
    }

    @Test
    fun `scope ALL passes copyLineItemsFromAnchor=true to updateRecurringAppointmentHandler`() {
        val cmdSlot = slot<UpdateRecurringAppointmentCommand>()
        coEvery { updateRecurringAppointmentHandler.handle(capture(cmdSlot)) } returns recurringResult

        mockMvc.perform(updateRequest(scope = "ALL"))
            .andExpect(status().isOk)

        assertTrue(cmdSlot.captured.copyLineItemsFromAnchor)
    }

    // ─── scope=THIS_AND_FUTURE ────────────────────────────────────────────────

    @Test
    fun `scope THIS_AND_FUTURE calls updateRecurringAppointmentHandler`() {
        mockMvc.perform(updateRequest(scope = "THIS_AND_FUTURE"))
            .andExpect(status().isOk)

        coVerify(exactly = 1) { updateRecurringAppointmentHandler.handle(any()) }
    }

    @Test
    fun `scope THIS_AND_FUTURE passes THIS_AND_FUTURE scope to handler`() {
        val cmdSlot = slot<UpdateRecurringAppointmentCommand>()
        coEvery { updateRecurringAppointmentHandler.handle(capture(cmdSlot)) } returns recurringResult

        mockMvc.perform(updateRequest(scope = "THIS_AND_FUTURE"))
            .andExpect(status().isOk)

        assertEquals(pl.detailing.crm.appointment.recurrence.update.RecurrenceEditScope.THIS_AND_FUTURE, cmdSlot.captured.scope)
    }

    // ─── scope=THIS ───────────────────────────────────────────────────────────

    @Test
    fun `scope THIS does not call updateRecurringAppointmentHandler`() {
        mockMvc.perform(updateRequest(scope = "THIS"))
            .andExpect(status().isOk)

        coVerify(exactly = 0) { updateRecurringAppointmentHandler.handle(any()) }
    }

    @Test
    fun `scope THIS still calls updateAppointmentHandler`() {
        mockMvc.perform(updateRequest(scope = "THIS"))
            .andExpect(status().isOk)

        coVerify(exactly = 1) { updateAppointmentHandler.handle(any()) }
    }

    // ─── no scope ─────────────────────────────────────────────────────────────

    @Test
    fun `no scope does not call updateRecurringAppointmentHandler`() {
        mockMvc.perform(updateRequest(scope = null))
            .andExpect(status().isOk)

        coVerify(exactly = 0) { updateRecurringAppointmentHandler.handle(any()) }
    }

    @Test
    fun `no scope calls updateAppointmentHandler`() {
        mockMvc.perform(updateRequest(scope = null))
            .andExpect(status().isOk)

        coVerify(exactly = 1) { updateAppointmentHandler.handle(any()) }
    }

    // ─── case insensitivity ───────────────────────────────────────────────────

    @Test
    fun `scope all lowercase is accepted`() {
        mockMvc.perform(updateRequest(scope = "all"))
            .andExpect(status().isOk)

        coVerify(exactly = 1) { updateRecurringAppointmentHandler.handle(any()) }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun givenAuthenticatedOwner() {
        val principal = UserPrincipal(
            userId = userId,
            studioId = studioId,
            role = UserRole.OWNER,
            email = "owner@detailboost.pl",
            fullName = "Jan Właściciel",
            phoneNumber = "+48600000000"
        )
        SecurityContextHolder.setContext(SecurityContextImpl(principal))
    }

    private fun updateRequest(scope: String?) = run {
        val url = if (scope != null)
            "/api/v1/appointments/${appointmentId.value}?scope=$scope"
        else
            "/api/v1/appointments/${appointmentId.value}"

        put(url)
            .contentType(MediaType.APPLICATION_JSON)
            .content(appointmentRequestBody())
    }

    private fun appointmentRequestBody() = """
        {
          "customer": {
            "mode": "EXISTING",
            "id": "${UUID.randomUUID()}"
          },
          "vehicle": {
            "mode": "NONE"
          },
          "services": [
            {
              "serviceId": "${UUID.randomUUID()}",
              "serviceName": "Detailing premium",
              "basePriceNet": 30000,
              "vatRate": 0,
              "adjustment": { "type": "FIXED_GROSS", "value": 0 },
              "note": ""
            }
          ],
          "schedule": {
            "isAllDay": false,
            "startDateTime": "2026-06-05T10:00:00Z",
            "endDateTime": "2026-06-05T18:00:00Z"
          },
          "appointmentColorId": "${UUID.randomUUID()}"
        }
    """.trimIndent()
}
