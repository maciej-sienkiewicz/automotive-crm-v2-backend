package pl.detailing.crm.shared.pii

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.PII_MASK

class PiiMaskingModuleTest {

    private val mapper: ObjectMapper = ObjectMapper().registerModule(PiiMaskingModule())

    data class CustomerDto(
        val id: String,
        @Pii val firstName: String?,
        @Pii val phone: String?,
        val companyName: String?
    )

    data class BrokenDto(@Pii val age: Int)

    @AfterEach
    fun tearDown() = PiiAccessContext.clear()

    @Test
    fun `no thread context means masked - deny by default`() {
        PiiAccessContext.clear()
        val json = mapper.writeValueAsString(CustomerDto("c1", "Jan", "501502503", "ACME"))
        assertTrue(json.contains("\"firstName\":\"$PII_MASK\"")) { json }
        assertTrue(json.contains("\"phone\":\"$PII_MASK\"")) { json }
        // Non-annotated fields are untouched
        assertTrue(json.contains("\"companyName\":\"ACME\"")) { json }
        assertTrue(json.contains("\"id\":\"c1\"")) { json }
    }

    @Test
    fun `granted context serializes real values`() {
        PiiAccessContext.withGranted {
            val json = mapper.writeValueAsString(CustomerDto("c1", "Jan", "501502503", "ACME"))
            assertTrue(json.contains("\"firstName\":\"Jan\"")) { json }
            assertTrue(json.contains("\"phone\":\"501502503\"")) { json }
        }
    }

    @Test
    fun `masked context wins even when opened inside a granted scope`() {
        PiiAccessContext.withGranted {
            PiiAccessContext.withMasked {
                val json = mapper.writeValueAsString(CustomerDto("c1", "Jan", null, null))
                assertTrue(json.contains("\"firstName\":\"$PII_MASK\"")) { json }
            }
            // Previous (granted) decision is restored afterwards
            assertTrue(PiiAccessContext.isGranted())
        }
    }

    @Test
    fun `null values stay null instead of leaking value presence as a mask`() {
        PiiAccessContext.open(PiiAccess.MASKED)
        val json = mapper.writeValueAsString(CustomerDto("c1", null, null, null))
        assertTrue(json.contains("\"firstName\":null")) { json }
    }

    @Test
    fun `open and clear drive the decision per thread`() {
        PiiAccessContext.open(PiiAccess.GRANTED)
        assertTrue(PiiAccessContext.isGranted())
        PiiAccessContext.clear()
        assertEquals(PiiAccess.MASKED, PiiAccessContext.current())
    }

    @Test
    fun `@Pii on a non-String field fails fast instead of shipping silently`() {
        assertThrows(Exception::class.java) {
            mapper.writeValueAsString(BrokenDto(42))
        }
    }
}
