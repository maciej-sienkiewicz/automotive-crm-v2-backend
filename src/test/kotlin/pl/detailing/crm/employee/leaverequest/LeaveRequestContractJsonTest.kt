package pl.detailing.crm.employee.leaverequest

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.reflect.full.memberProperties

/**
 * Kształt JSON-a wniosku względem `docs/api-leave-requests.md` (zmiany v2): pola usunięte
 * z kontraktu nie wracają w odpowiedziach, a przysłane przez starego klienta nie psują
 * żądania.
 */
class LeaveRequestContractJsonTest {

    // Najostrzejsza konfiguracja: nieznane pole = błąd. DTO ma przejść mimo to, bo
    // ignorowanie `substituteEmployeeId` nie może zależeć od globalnych ustawień Jacksona.
    private val strictMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Test
    fun `substitute sent by an old client is ignored`() {
        val body = strictMapper.readValue<CreateLeaveRequestRequest>(
            """{"leaveType":"ANNUAL","onDemand":false,"startDate":"2026-11-03","endDate":"2026-11-07",""" +
                """"reason":"Wyjazd","substituteEmployeeId":"0b3c8e0e-1111-4c1a-9d7e-2f1e0c9a7b10"}"""
        )
        assertEquals("ANNUAL", body.leaveType)
        assertEquals(LocalDate.of(2026, 11, 3), body.startDate)
        assertEquals("Wyjazd", body.reason)
    }

    @Test
    fun `responses carry neither the substitute nor the approval basis`() {
        val removed = setOf("substituteEmployeeId", "substituteName", "decidedByBasis", "decidedByRoleName")
        listOf(LeaveRequestSummaryResponse::class, LeaveRequestDetailResponse::class).forEach { type ->
            val fields = type.memberProperties.map { it.name }.toSet()
            assertFalse(fields.any { it in removed }, "${type.simpleName} nadal ma ${fields.intersect(removed)}")
        }
    }
}
