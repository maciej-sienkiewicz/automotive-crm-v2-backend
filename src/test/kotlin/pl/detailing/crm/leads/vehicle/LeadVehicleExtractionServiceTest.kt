package pl.detailing.crm.leads.vehicle

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import pl.detailing.crm.vehicle.VehicleCatalogMatcher

/**
 * Marka i model zapisane przy leadzie muszą pochodzić z katalogu pojazdów, a nie być
 * tym, co akurat napisał klient. Inaczej „bmw", „BMW" i „beemer" są w bazie trzema
 * markami i przestaje działać wszystko, po co to pole istnieje: filtr, zestawienie
 * „ile pytań o BMW" i powiązanie z kartoteką pojazdu.
 */
class LeadVehicleExtractionServiceTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()
    private val catalogMatcher = mockk<VehicleCatalogMatcher>()

    private val service = LeadVehicleExtractionService(chatClient, catalogMatcher)

    @BeforeEach
    fun setUp() {
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
    }

    private fun stubRead(brand: String?, model: String?) {
        every { callSpec.entity(LeadVehicleExtractionService.RawVehicle::class.java) } returns
            LeadVehicleExtractionService.RawVehicle(brand, model)
    }

    @Test
    fun `zapisuje wartosci z katalogu, nie slowa klienta`() = runBlocking {
        stubRead("bmw", "m3")
        coEvery { catalogMatcher.resolve("bmw", "m3") } returns VehicleCatalogMatcher.Match("Bmw", "M3")

        val result = service.extract("Klient: mam bmw m3 g80 z 2023, chce folie")

        assertEquals("Bmw", result.brand)
        assertEquals("M3", result.model)
    }

    @Test
    fun `marka nierozpoznana przez katalog nie trafia do bazy`() = runBlocking {
        stubRead("krzeslo biurowe", null)
        coEvery { catalogMatcher.resolve(any(), any()) } returns VehicleCatalogMatcher.Match(null, null)

        val result = service.extract("Klient: pytanie o cos zupelnie innego")

        assertNull(result.brand, "Puste pole jest lepsze niz smiec w polu marki")
        assertNull(result.model)
    }

    @Test
    fun `bez marki w rozmowie nie pytamy nawet katalogu`() = runBlocking {
        stubRead(null, null)

        val result = service.extract("Klient: dzien dobry, poprosze o wycene")

        assertNull(result.brand)
        coVerify(exactly = 0) { catalogMatcher.resolve(any(), any()) }
    }

    @Test
    fun `pusta rozmowa nie uruchamia modelu`() = runBlocking {
        val result = service.extract("   ")

        assertNull(result.brand)
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `awaria LLM nie wywraca tworzenia leada`() = runBlocking {
        every { callSpec.entity(LeadVehicleExtractionService.RawVehicle::class.java) } throws
            RuntimeException("timeout")

        val result = service.extract("Klient: mam bmw m3")

        assertNull(result.brand, "Lead ma powstac takze wtedy, gdy rozpoznanie auta zawiedzie")
    }
}
