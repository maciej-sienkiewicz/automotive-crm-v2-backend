package pl.detailing.crm.service.taxonomy

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Rodzina usługi detailingowej — zamknięta taksonomia, do której klasyfikujemy
 * KAŻDĄ nazwę usługi w systemie (z cennika i wpisaną z ręki).
 *
 * Po co rodzina, skoro są nazwy: nazwy są w każdym studiu inne („PPF przód",
 * „Folia ochronna cały przód", „Full front"), a pytanie „czy to ta sama robota"
 * wymaga wspólnego mianownika. Rodzina jest tym mianownikiem.
 *
 * PPF i WRAP to celowo DWIE rodziny (decyzja właściciela produktu): folia ochronna
 * i oklejenie zmieniające kolor to inna robota, inny materiał i inna rozmowa
 * o cenie — mimo że słowo „oklejanie" pada w obu.
 */
enum class ServiceFamily {
    CERAMIC_COATING,
    PPF,
    WRAP,
    CORRECTION_POLISH,
    INTERIOR,
    WASH,
    GLASS,
    WHEELS,
    ENGINE_BAY,
    FULL_DETAILING,
    OTHER,
    UNKNOWN;

    companion object {
        fun from(raw: String?): ServiceFamily =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Zakres roboty wyczytany z samej nazwy — „cały przód" vs „full body" vs nic. */
enum class ServiceScope {
    FULL,
    PARTIAL,
    UNKNOWN;

    companion object {
        fun from(raw: String?): ServiceScope =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * Klucz wyszukiwania nazwy: lower + trim + zbite spacje. Diakrytyki zostają —
 * porównujemy nazwy z tej samej bazy, nie z klawiatury klienta.
 */
fun serviceNameKey(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), " ").take(220)

/**
 * Zapamiętana klasyfikacja jednej NAZWY usługi.
 *
 * Wiersz globalny ([GLOBAL_STUDIO] jako studio_id) — „Powłoka ceramiczna" znaczy
 * to samo w każdym studiu, więc drugie studio płaci zero. Wiersz per studio
 * (source=MANUAL) nadpisuje globalny; automat nigdy go nie tworzy ani nie rusza.
 */
@Entity
@Table(name = "service_families")
class ServiceFamilyEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name_key", nullable = false, length = 220)
    val nameKey: String,

    @Column(name = "name_sample", nullable = false, length = 220)
    val nameSample: String,

    @Column(name = "family", nullable = false, length = 30)
    var family: String,

    @Column(name = "scope", nullable = false, length = 20)
    var scope: String,

    @Column(name = "source", nullable = false, length = 20)
    var source: String = "LLM",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    companion object {
        /** Sentinel wiersza globalnego — w indeksie unikalnym NULL nie równa się NULL. */
        val GLOBAL_STUDIO: UUID = UUID(0, 0)
    }
}

@Repository
interface ServiceFamilyRepository : JpaRepository<ServiceFamilyEntity, UUID> {
    fun findByStudioIdInAndNameKeyIn(
        studioIds: Collection<UUID>,
        nameKeys: Collection<String>
    ): List<ServiceFamilyEntity>
}

@Configuration
class ServiceFamilyAiConfig {

    /** Klasyfikacja faktu, nie twórczość — temperatura 0. */
    @Bean("serviceFamilyChatClient")
    fun serviceFamilyChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.service-family.model:gpt-4o-mini}") model: String
    ): ChatClient =
        builder
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.0)
                    .build()
            )
            .build()
}

/** Rozstrzygnięta klasyfikacja jednej nazwy — to, co czyta dopasowanie. */
data class ClassifiedServiceName(
    val nameKey: String,
    val family: ServiceFamily,
    val scope: ServiceScope
)

/**
 * Przypisuje nazwie usługi rodzinę i zakres — raz na nazwę, potem już tylko z bazy.
 *
 * Wzorzec 1:1 z [pl.detailing.crm.vehicle.segment.VehicleSegmentService]: pytanie
 * „czym jest Korekta lakieru 2-etapowa" ma jedną odpowiedź dla całego świata.
 * Odpytywanie modelu przy każdym zapytaniu byłoby płaceniem w kółko za tę samą
 * odpowiedź. Nowe nazwy idą do modelu WSADOWO (do [BATCH_SIZE] naraz), z numerami
 * pozycji zamiast identyfikatorów — numer kosztuje 2 tokeny, UUID 33.
 */
@Service
class ServiceFamilyClassifier(
    @Qualifier("serviceFamilyChatClient") private val chatClient: ChatClient,
    private val repository: ServiceFamilyRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Zwraca klasyfikację dla każdej ROZSTRZYGNIĘTEJ nazwy; brakujące klasyfikuje
     * i zapisuje. Wiersz studia (ręczna poprawka) wygrywa z globalnym.
     *
     * Nazwa, dla której model zawiódł, jest w wyniku NIEOBECNA — nie wraca jako
     * UNKNOWN. Różnica jest istotna: UNKNOWN to zapisany werdykt („nazwa nic nie
     * mówi"), a nieobecność to „spróbuj ponownie". Zlanie ich w jedno zamroziłoby
     * chwilową awarię API jako wieczną klasyfikację.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun classify(studioId: UUID, names: Collection<String>): Map<String, ClassifiedServiceName> {
        val samples = names
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associateBy { serviceNameKey(it) }
        if (samples.isEmpty()) return emptyMap()

        val known = repository
            .findByStudioIdInAndNameKeyIn(listOf(studioId, ServiceFamilyEntity.GLOBAL_STUDIO), samples.keys)
            // Wiersz studia po wierszu globalnym — associateBy zostawia ostatni, więc nadpisuje.
            .sortedBy { if (it.studioId == ServiceFamilyEntity.GLOBAL_STUDIO) 0 else 1 }
            .associateBy { it.nameKey }
            .toMutableMap()

        val missing = samples.keys - known.keys
        missing.chunked(BATCH_SIZE).forEach { chunk ->
            val answers = ask(chunk.map { samples.getValue(it) })
            chunk.forEachIndexed { index, nameKey ->
                val answer = answers[index] ?: return@forEachIndexed
                val entity = ServiceFamilyEntity(
                    studioId = ServiceFamilyEntity.GLOBAL_STUDIO,
                    nameKey = nameKey,
                    nameSample = samples.getValue(nameKey).take(220),
                    family = ServiceFamily.from(answer.family).name,
                    scope = ServiceScope.from(answer.scope).name
                )
                known[nameKey] = try {
                    repository.save(entity)
                } catch (e: DataIntegrityViolationException) {
                    // Dwa uzgadniacze na tej samej nazwie — wygrywa pierwszy zapis.
                    repository
                        .findByStudioIdInAndNameKeyIn(listOf(ServiceFamilyEntity.GLOBAL_STUDIO), listOf(nameKey))
                        .firstOrNull() ?: return@forEachIndexed
                }
            }
        }

        return known.mapValues { (key, row) ->
            ClassifiedServiceName(
                nameKey = key,
                family = ServiceFamily.from(row.family),
                scope = ServiceScope.from(row.scope)
            )
        }
    }

    /** @return odpowiedź per pozycja wejścia (po numerze) albo null, gdy model zawiódł. */
    private fun ask(names: List<String>): Map<Int, RawAnswer> =
        try {
            val listing = names.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n")
            chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("NAZWY USŁUG DO SKLASYFIKOWANIA\n$listing")
                .call()
                .entity(RawAnswers::class.java)
                ?.results
                ?.mapNotNull { r -> r.position?.let { it - 1 to r } }
                ?.filter { (i, _) -> i in names.indices }
                ?.toMap()
                .orEmpty()
        } catch (e: Exception) {
            log.warn("[SERVICE_FAMILY] Klasyfikacja {} nazw nie powiodła się: {}", names.size, e.message)
            emptyMap()
        }

    internal data class RawAnswers(
        @JsonProperty("results") val results: List<RawAnswer>? = null
    )

    internal data class RawAnswer(
        @JsonProperty("position") val position: Int? = null,
        @JsonProperty("family") val family: String? = null,
        @JsonProperty("scope") val scope: String? = null
    )

    companion object {
        const val BATCH_SIZE = 50

        internal val SYSTEM_PROMPT = """
Klasyfikujesz nazwy usług studia detailingu samochodowego do zamkniętej taksonomii.
Odpowiadasz wyłącznie kodami z poniższych list — nic innego nie jest dozwolone.

family — rodzina roboty:
  CERAMIC_COATING   powłoki ceramiczne, kwarcowe, grafenowe („ceramika", „9H")
  PPF               folia OCHRONNA bezbarwna (paint protection film, „folia na przód")
  WRAP              oklejenie ZMIENIAJĄCE WYGLĄD: kolor, carbon, mat („zmiana koloru")
  CORRECTION_POLISH korekta lakieru, polerowanie, usuwanie zarysowań
  INTERIOR          wnętrze: pranie tapicerki, czyszczenie skóry, ozonowanie kabiny
  WASH              mycie, pielęgnacja, woski, quick detailing
  GLASS             szyby: powłoki hydrofobowe, polerowanie szyb, przyciemnianie
  WHEELS            felgi i opony: czyszczenie, zabezpieczenie, renowacja
  ENGINE_BAY        komora silnika
  FULL_DETAILING    pakiety obejmujące całość auta na raz („pełny detailing")
  OTHER             usługa spoza powyższych (naprawa, dorabianie, transport)
  UNKNOWN           nazwa nie mówi, co to za robota („Pakiet 1", „Wariant B")

scope — zakres wyczytany z SAMEJ nazwy:
  FULL     nazwa mówi o całym aucie („full body", „całe auto", „kompleksowy")
  PARTIAL  nazwa wskazuje fragment („przód", „maska", „zderzak", „1-etapowa" NIE jest fragmentem)
  UNKNOWN  nazwa nie mówi nic o zakresie

ZASADY:
- PPF i WRAP to DWIE RÓŻNE rodziny. „Oklejanie" bez wskazania ochrony czy koloru → UNKNOWN
  scope i rodzina wg pozostałych słów; gdy nie sposób rozstrzygnąć folii → PPF tylko przy
  jawnym „PPF"/„ochronna", inaczej UNKNOWN.
- Nie zgaduj. UNKNOWN jest lepsze niż pewnie brzmiący błąd: na tej klasyfikacji
  ktoś oprze porównanie cen.
- Klasyfikujesz NAZWĘ, nie wyobrażenie o studiu. „Ochrona lakieru" bez dalszych słów
  nie rozstrzyga między ceramiką a folią → UNKNOWN.

ODPOWIEDŹ: dla KAŻDEJ pozycji zwróć { position: numer z listy, family, scope }.
""".trim()
    }
}
