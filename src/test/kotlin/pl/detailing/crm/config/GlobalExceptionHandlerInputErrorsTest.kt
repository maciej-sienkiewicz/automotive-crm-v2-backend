package pl.detailing.crm.config

import io.mockk.mockk
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.shared.Money
import pl.detailing.crm.visit.domain.IllegalStateTransitionException
import java.util.UUID

/**
 * Invalid Data — kontrakt odpowiedzi na złośliwe / zepsute wejście.
 *
 * Przed poprawką każdy z tych przypadków lądował w `handleGeneric` jako HTTP 500 ze
 * stack-trace'em na poziomie ERROR — do wywołania dowolną liczbę razy przez każdego.
 */
class GlobalExceptionHandlerInputErrorsTest {

    data class SampleRequest(
        @field:NotBlank @field:Size(max = 5) val name: String,
        @field:Min(0) val amount: Long
    )

    @RestController
    @RequestMapping("/probe")
    class ProbeController {
        @PostMapping("/valid")
        fun valid(@Valid @RequestBody body: SampleRequest) = mapOf("ok" to true)

        @GetMapping("/uuid/{id}")
        fun uuid(@PathVariable id: String) = mapOf("id" to UUID.fromString(id).toString())

        @GetMapping("/typed/{id}")
        fun typed(@PathVariable id: UUID, @RequestParam page: Int) = mapOf("id" to id.toString(), "page" to page)

        @GetMapping("/money")
        fun money(@RequestParam cents: Long) = mapOf("cents" to Money(cents).amountInCents)

        @GetMapping("/transition")
        fun transition(): Nothing = throw IllegalStateTransitionException("Nie można zmieniać usług wizyty w statusie COMPLETED")
    }

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ProbeController())
            .setControllerAdvice(GlobalExceptionHandler(mockk<TenantIsolationAuditService>(relaxed = true)))
            .build()
    }

    @Test
    fun `bean validation failure is 400 with the offending fields listed`() {
        mockMvc.perform(
            post("/probe/valid").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"toolongname","amount":-1}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Błąd walidacji"))
            .andExpect(jsonPath("$.fieldErrors.length()").value(2))
    }

    @Test
    fun `malformed JSON is 400 and the framework message is not echoed`() {
        mockMvc.perform(
            post("/probe/valid").contentType(MediaType.APPLICATION_JSON).content("""{"name": """)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Żądanie zawiera nieprawidłowe lub brakujące dane"))
    }

    @Test
    fun `wrong JSON type for a field is 400`() {
        mockMvc.perform(
            post("/probe/valid").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ok","amount":"' OR 1=1"}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `UUID fromString on a path variable is 400 - not 500`() {
        mockMvc.perform(get("/probe/uuid/not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Nieprawidłowe żądanie"))
    }

    @Test
    fun `type mismatch in path or query is 400`() {
        mockMvc.perform(get("/probe/typed/${UUID.randomUUID()}").param("page", "abc"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/probe/typed/xyz").param("page", "1"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `missing required parameter is 400`() {
        mockMvc.perform(get("/probe/typed/${UUID.randomUUID()}"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `negative Money (value-class invariant) is 400`() {
        mockMvc.perform(get("/probe/money").param("cents", "-100"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `state-machine refusal is 409 conflict`() {
        mockMvc.perform(get("/probe/transition"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("Konflikt stanu"))
    }
}
