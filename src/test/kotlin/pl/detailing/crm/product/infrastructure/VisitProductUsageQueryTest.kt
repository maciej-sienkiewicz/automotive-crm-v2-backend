package pl.detailing.crm.product.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

/**
 * Agregat „w ilu wizytach użyto produktu" przeciwko prawdziwemu Postgresowi.
 *
 * Dowodzi jednej rzeczy, której nie da się sprawdzić bez bazy: `COUNT(DISTINCT vp.visitId)`
 * liczy WIZYTY, nie wiersze. Tabela `visit_products` świadomie nie ma klucza unikalnego
 * (visit_id, product_id) - ten sam produkt bywa dopięty do jednej wizyty przez dwie osoby -
 * więc `COUNT(*)` pokazywałby w tabeli produktów liczbę wyższą niż liczba wizyt, w których
 * ten produkt faktycznie poszedł w ruch.
 *
 * Przy okazji weryfikuje samo zapytanie: JPQL z konstruktorem (`SELECT new ...`) jest
 * napisem, którego kompilator Kotlina nie sprawdza - literówka w nazwie klasy wychodzi
 * dopiero przy starcie aplikacji.
 *
 * Tak jak UpdateAppointmentHandlerPersistenceTest jest oznaczony `@Tag("testcontainers")`
 * i WYŁĄCZONY z domyślnego `./gradlew test` (patrz `tasks.withType<Test>` w build.gradle.kts);
 * uruchamia się go przez `./gradlew test -PrunTestcontainers`. W środowisku, w którym
 * powstał, Docker był niedostępny (`docker info` failed), więc plik jest sprawdzony pod
 * kątem kompilacji, ale NIE uruchomiony.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class VisitProductUsageQueryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired lateinit var repository: VisitProductRepository

    private val studioId = UUID.randomUUID()
    private val otherStudioId = UUID.randomUUID()

    private fun link(productId: UUID, visitId: UUID, studio: UUID = studioId, at: Instant = Instant.now()) =
        repository.save(
            VisitProductEntity(
                id = UUID.randomUUID(),
                studioId = studio,
                visitId = visitId,
                productId = productId,
                note = null,
                createdBy = UUID.randomUUID(),
                createdByName = "Anna Kowalska",
                createdAt = at
            )
        )

    @Test
    fun `liczy wizyty, nie wiersze - dwa dopiecia tego samego produktu do jednej wizyty to jedno uzycie`() {
        val product = UUID.randomUUID()
        val visit = UUID.randomUUID()
        link(product, visit)
        link(product, visit)
        link(product, UUID.randomUUID())

        val row = repository.usageByProduct(studioId, listOf(product)).single()

        assertEquals(2L, row.visitCount)
    }

    @Test
    fun `oddaje date ostatniego uzycia`() {
        val product = UUID.randomUUID()
        val older = Instant.parse("2026-01-10T08:00:00Z")
        val newest = Instant.parse("2026-05-14T10:15:00Z")
        link(product, UUID.randomUUID(), at = older)
        link(product, UUID.randomUUID(), at = newest)

        val row = repository.usageByProduct(studioId, listOf(product)).single()

        assertEquals(newest, row.lastUsedAt)
        assertEquals(2L, row.visitCount)
    }

    @Test
    fun `nie przecieka miedzy studiami`() {
        val product = UUID.randomUUID()
        link(product, UUID.randomUUID(), studio = otherStudioId)

        assertTrue(repository.usageByProduct(studioId, listOf(product)).isEmpty())
    }

    @Test
    fun `produkt bez ani jednego uzycia nie ma wiersza w wyniku`() {
        val used = UUID.randomUUID()
        val unused = UUID.randomUUID()
        link(used, UUID.randomUUID())

        val rows = repository.usageByProduct(studioId, listOf(used, unused))

        assertEquals(listOf(used), rows.map { it.productId })
    }
}
