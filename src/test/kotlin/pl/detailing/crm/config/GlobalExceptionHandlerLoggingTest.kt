package pl.detailing.crm.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.service.update.UpdateServiceRequest
import pl.detailing.crm.shared.ServiceId

/**
 * Log ostrzeżenia 4xx ma wskazać przyczynę bez sięgania po DevTools klienta.
 *
 * Zgłoszenie z produkcji zostawiło w logu wyłącznie:
 * `IllegalArgumentException [studioId=…, userId=…]: Invalid UUID string: temp-1790326210623`
 * - bez endpointu i bez miejsca w kodzie. Przyczynę (usługa spoza cennika w
 * `POST /services/update`) trzeba było odtworzyć ze zrzutów ekranu z przeglądarki.
 */
class GlobalExceptionHandlerLoggingTest {

    @RestController
    @RequestMapping("/api/v1/probe")
    class ProbeController {
        /** Stary kształt `ServiceController.updateService`: goły `ServiceId.fromString`. */
        @PostMapping("/raw-update")
        fun rawUpdate(@RequestBody request: UpdateServiceRequest) =
            mapOf("id" to ServiceId.fromString(request.originalServiceId).toString())

        /** Obecny kształt: walidacja z nazwą pola. */
        @PostMapping("/update")
        fun update(@RequestBody request: UpdateServiceRequest) =
            mapOf("id" to request.originalServiceIdOrReject().toString())
    }

    private val handlerLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private lateinit var mockMvc: MockMvc

    private val tempServiceBody = """
        {"originalServiceId":"temp-1790326470364","name":"powłoka na felgi",
         "basePriceNet":73171,"basePriceGross":90000,"vatRate":23,"requireManualPrice":false}
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        appender.start()
        handlerLogger.addAppender(appender)
        mockMvc = MockMvcBuilders.standaloneSetup(ProbeController())
            .setControllerAdvice(GlobalExceptionHandler(mockk<TenantIsolationAuditService>(relaxed = true)))
            .build()
    }

    @AfterEach
    fun tearDown() {
        handlerLogger.detachAppender(appender)
    }

    /**
     * Ostatni wpis z WĄTKU TEGO TESTU. Logger handlera jest wspólny, a klasy testowe
     * biegną równolegle - bez filtra łapał się wpis z innego testu (`POST /probe/valid`).
     * MockMvc wykonuje żądanie synchronicznie, na wątku wywołującym.
     */
    private fun lastWarning(): String {
        val thread = Thread.currentThread().name
        return appender.list.last { it.threadName == thread }.formattedMessage
    }

    @Test
    fun `IllegalArgumentException loguje endpoint i miejsce w naszym kodzie, a nie w ServiceId`() {
        mockMvc.perform(post("/api/v1/probe/raw-update").contentType(MediaType.APPLICATION_JSON).content(tempServiceBody))
            .andExpect(status().isBadRequest)

        val line = lastWarning()
        assertThat(line).contains("POST /api/v1/probe/raw-update")
        assertThat(line).contains("Invalid UUID string: temp-1790326470364")
        // Wywołujący, nie `ServiceId.fromString` z pakietu shared.
        assertThat(line).contains("ProbeController.rawUpdate(GlobalExceptionHandlerLoggingTest.kt:")
        assertThat(line).doesNotContain("ValueClasses.kt")
    }

    @Test
    fun `usługa spoza cennika w update daje 400 z nazwą pola, a log mówi skąd przyszła`() {
        mockMvc.perform(post("/api/v1/probe/update").contentType(MediaType.APPLICATION_JSON).content(tempServiceBody))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Błąd walidacji"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("nie jest zapisana w cenniku")))

        val line = lastWarning()
        assertThat(line).startsWith("ValidationException")
        assertThat(line).contains("POST /api/v1/probe/update")
        assertThat(line).contains("originalServiceId")
        assertThat(line).contains("temp-1790326470364")
    }

    @Test
    fun `zepsute JSON-owe body też nazywa endpoint`() {
        mockMvc.perform(post("/api/v1/probe/update").contentType(MediaType.APPLICATION_JSON).content("""{"name": """))
            .andExpect(status().isBadRequest)

        assertThat(lastWarning()).contains("POST /api/v1/probe/update")
    }

    @Test
    fun `query string nie trafia do logu - niesie frazy wyszukiwania z danymi osobowymi`() {
        mockMvc.perform(
            post("/api/v1/probe/raw-update").param("search", "Dariusz")
                .contentType(MediaType.APPLICATION_JSON).content(tempServiceBody)
        ).andExpect(status().isBadRequest)

        assertThat(lastWarning()).doesNotContain("Dariusz")
    }
}
