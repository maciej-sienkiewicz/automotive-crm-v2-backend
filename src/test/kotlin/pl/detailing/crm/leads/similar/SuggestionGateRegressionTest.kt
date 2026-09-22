package pl.detailing.crm.leads.similar

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.service.taxonomy.ClassifiedServiceName
import pl.detailing.crm.service.taxonomy.ServiceFamily
import pl.detailing.crm.service.taxonomy.ServiceFamilyClassifier
import pl.detailing.crm.service.taxonomy.ServiceOperation
import pl.detailing.crm.service.taxonomy.ServicePart
import pl.detailing.crm.service.taxonomy.ServiceScope
import pl.detailing.crm.service.taxonomy.serviceNameKey
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * INCYDENT PRODUKCYJNY „renowacja reflektorów" jako test regresji — dosłownie ta treść,
 * ten cennik i te dwie pozycje, które trafiły na ekran właściciela.
 *
 * Lead cfcf7911-015d-4e1b-94b7-54e78b9fac2f (Volkswagen Passat B7). Klient prosi
 * o wycenę renowacji przednich lamp. System podsunął „Oklejenie reflektorów folią
 * ochronną" (300 zł) i „Okresowy serwis powłoki ceramicznej" (499 zł), a potencjał
 * leada ustawił na 799 zł — czyli na sumę dwóch pozycji, których nikt nie zamawiał.
 *
 * Zapisany werdykt modelu pokazał trzy niezależne usterki naraz:
 *   needs    = CORRECT:LAMPS:FULL|PROTECT:LAMPS:FULL  — JEDNA robota rozbita na dwie,
 *   families = CORRECTION_POLISH                      — rodzina właściwa dla roboty...
 *   pozycje  z rodzin PPF i CERAMIC_COATING           — ...a pozycje z dwóch innych,
 *   anchor   = 79900 gr                               — błąd poszedł dalej, w kotwicę cen.
 *
 * Te testy MOCKUJĄ MODEL i sprawdzają logikę KODU: bramki, odrzucanie pozycji bez
 * cytatu, werdykt per potrzeba. Nie sprawdzają, czy model odpowiada dobrze — od tego
 * jest prompt i [SuggestionPromptContractTest].
 */
class SuggestionGateRegressionTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()
    private val intentRepository = mockk<LeadServiceIntentRepository>()
    private val serviceRepository = mockk<ServiceRepository>()
    private val classifier = mockk<ServiceFamilyClassifier>()
    private val verifier = mockk<LeadSuggestionVerifier>()
    private val decisionRepository = mockk<LeadSuggestionDecisionRepository>(relaxed = true)

    private val studioId = StudioId(UUID.randomUUID())
    private val leadId = UUID.fromString("cfcf7911-015d-4e1b-94b7-54e78b9fac2f")

    /** Treść maila klienta — przepisana z produkcji, bez jednego znaku zmiany. */
    private val query = "chciałbym zapytać o możliwość wykonania renowacji przednich lamp pojazd " +
        "Volkswagen Passat B7. Interesuje mnie kompleksowa renowacja lamp, obejmująca " +
        "usunięcie zmatowienia i zarysowań oraz zabezpieczenie powierzchni po " +
        "wykonanej usłudze. Proszę o informacje jaki jest koszt renowacji, dostępny " +
        "termin oraz czas realizacji"

    /** Cennik studia w kolejności, w jakiej widzi go model — nazwy i osie z produkcji. */
    private val catalog = listOf(
        axis("Oklejenie reflektorów folią ochronną", 30000, ServiceFamily.PPF, ServiceOperation.APPLY_FILM, ServicePart.LAMPS),
        axis("Okresowy serwis powłoki ceramicznej", 49900, ServiceFamily.CERAMIC_COATING, ServiceOperation.PROTECT, ServicePart.UNKNOWN),
        axis("Polerowanie szyby czołowej", 50000, ServiceFamily.GLASS, ServiceOperation.CORRECT, ServicePart.GLASS),
        axis("Zabezpieczenie lakieru powłoką ceramiczną wariant Standard", 220000, ServiceFamily.CERAMIC_COATING, ServiceOperation.PROTECT, ServicePart.UNKNOWN)
    )

    private val service = LeadServiceIntentService(
        chatClient, intentRepository, serviceRepository, "gpt-4.1-mini",
        classifier, null, null, null, verifier, decisionRepository
    )

    @BeforeEach
    fun setUp() {
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
        every { intentRepository.findById(leadId) } returns java.util.Optional.empty()
        every { intentRepository.save(any()) } answers { firstArg() }
        every { serviceRepository.findByStudioId(studioId.value) } returns catalog.map { it.first }
        every { classifier.classify(studioId.value, any()) } returns
            catalog.associate { (entity, classified) -> serviceNameKey(entity.name) to classified }
        // Domyślnie weryfikator przepuszcza wszystko — chodzi o to, żeby testy bramek
        // kodu nie przechodziły „przypadkiem", dlatego że drugi model coś odsiał.
        every { verifier.verify(any(), any()) } answers {
            secondArg<List<VerifiedCandidate>>().associate { it.candidateId to SuggestionVerdict(true, null) }
        }
    }

    // ═══ PRZYPADEK PRODUKCYJNY ═══════════════════════════════════════════════════

    /**
     * Rdzeń incydentu. Model powtarza dokładnie swój produkcyjny błąd: rozbija jedną
     * robotę na dwie potrzeby i podczepia pod nie pozycje z obcych rodzin, z poprawnymi
     * cytatami z maila. Cytaty NIE wystarczają — o odrzuceniu decydują rodziny i osie.
     */
    @Test
    fun `renowacja reflektorow nie dostaje ani folii, ani serwisu powloki`() {
        stub(
            reasoning = "Klient pyta o renowację lamp.",
            families = listOf("CORRECTION_POLISH"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "MATCHED", main = true,
                    quote = "kompleksowa renowacja lamp",
                    services = listOf(svc(1, "usunięcie zmatowienia i zarysowań", "ANSWER"))
                ),
                need(
                    "PROTECT", "LAMPS", "FULL", "MATCHED", main = false,
                    quote = "zabezpieczenie powierzchni po wykonanej usłudze",
                    services = listOf(svc(2, "zabezpieczenie powierzchni po wykonanej usłudze", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, query)!!

        assertEquals(emptyList<SuggestedService>(), intent.suggestions, "Żadna z tych pozycji nie jest odpowiedzią na to pytanie")
        assertTrue(intent.matchedServiceIds.isEmpty())
        assertEquals(
            null, intent.anchorGross,
            "Kotwica liczona z pozycji, które przeżyły bramki — 799 zł z dwóch obcych pozycji zatruwało pasmo cen"
        )
        // Obie poległy na rodzinie: model zadeklarował CORRECTION_POLISH i wskazał
        // pozycje z PPF i CERAMIC_COATING. Dokładnie ta sprzeczność stoi w danych
        // produkcyjnych i do v3 nikt jej nie sprawdzał.
        assertEquals(
            listOf(LeadServiceIntentService.STAGE_FAMILY_MISMATCH, LeadServiceIntentService.STAGE_FAMILY_MISMATCH),
            slotOfDecisions().map { it.stage }
        )
    }

    /**
     * Ten sam incydent, ale model jest ze sobą SPÓJNY: deklaruje rodziny pasujące do
     * własnych wyborów, więc bramka rodzin milczy. Zostają osie i weryfikator.
     *
     * Test istnieje po to, żeby ograniczenie było zapisane, a nie domyślane: folię
     * zatrzymują osie (CORRECT ≠ APPLY_FILM), ale serwisu powłoki NIE zatrzymuje nic
     * w kodzie — jego part to UNKNOWN, a UNKNOWN świadomie pomija bramkę osi, bo
     * w produkcyjnym cenniku ma je dwie trzecie pozycji. Tę jedną wyłapuje wyłącznie
     * drugi model. Gdyby weryfikator kiedyś zniknął z potoku, ten test zapali się
     * pierwszy.
     */
    @Test
    fun `gdy model jest spojny ze soba, serwis powloki zatrzymuje dopiero weryfikator`() {
        every { verifier.verify(any(), any()) } returns mapOf(1 to SuggestionVerdict(false, "serwis powłoki to nie renowacja lamp"))
        stub(
            families = listOf("PPF", "CERAMIC_COATING"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "MATCHED", main = true,
                    quote = "kompleksowa renowacja lamp",
                    services = listOf(svc(1, "usunięcie zmatowienia i zarysowań", "ANSWER"))
                ),
                need(
                    "PROTECT", "LAMPS", "FULL", "MATCHED", main = false,
                    quote = "zabezpieczenie powierzchni po wykonanej usłudze",
                    services = listOf(svc(2, "zabezpieczenie powierzchni po wykonanej usłudze", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, query)!!

        assertEquals(emptyList<SuggestedService>(), intent.suggestions)
        val stages = slotOfDecisions().associate { it.serviceName to it.stage }
        assertEquals(
            LeadServiceIntentService.STAGE_AXIS_MISMATCH, stages["Oklejenie reflektorów folią ochronną"],
            "Folię zatrzymuje bramka osi: renowacja to CORRECT, oklejanie to APPLY_FILM"
        )
        assertEquals(
            LeadServiceIntentService.STAGE_VERIFIER_REJECTED, stages["Okresowy serwis powłoki ceramicznej"],
            "Tej pozycji nie zatrzymuje żadna bramka kodu — part=UNKNOWN pomija osie"
        )
    }

    /** Ta sama treść, gdy model poprawnie rozpozna, że studio tej roboty nie ma. */
    @Test
    fun `glowna potrzeba spoza cennika daje not_in_catalog i pusta liste`() {
        stub(
            families = listOf("CORRECTION_POLISH"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "NOT_IN_CATALOG", main = true,
                    quote = "kompleksowa renowacja lamp", services = emptyList()
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, query)!!

        assertEquals(ServiceIntentStatus.NOT_IN_CATALOG, intent.status)
        assertEquals(emptyList<SuggestedService>(), intent.suggestions)
    }

    /**
     * Sedno H2: do v3 werdykt był JEDEN na całe zapytanie, więc poboczna potrzeba
     * z trafieniem w cennik robiła MATCHED z całości — i otwierała bramę wszystkiemu.
     * Teraz globalny status bierze się z potrzeby GŁÓWNEJ.
     */
    @Test
    fun `poboczna potrzeba w cenniku nie robi matched z calego zapytania`() {
        stub(
            families = listOf("CORRECTION_POLISH", "CERAMIC_COATING"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "NOT_IN_CATALOG", main = true,
                    quote = "kompleksowa renowacja lamp", services = emptyList()
                ),
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = false,
                    quote = "zabezpieczenie powierzchni po wykonanej usłudze",
                    services = listOf(svc(4, "zabezpieczenie powierzchni po wykonanej usłudze", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, query)!!

        assertEquals(
            ServiceIntentStatus.NOT_IN_CATALOG, intent.status,
            "Robota, o którą klient pyta, decyduje o werdykcie — nie ta, która akurat się znalazła"
        )
    }

    // ═══ BRAMKI KODU, PO JEDNEJ ══════════════════════════════════════════════════

    /** Cytat, którego w mailu nie ma, jest zmyśleniem — pozycja przepada. */
    @Test
    fun `pozycja z cytatem spoza tresci maila odpada`() {
        stub(
            families = listOf("CERAMIC_COATING"),
            needs = listOf(
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = true,
                    quote = "zabezpieczenie powierzchni po wykonanej usłudze",
                    services = listOf(svc(4, "klient prosi o powłokę ceramiczną na całe auto", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, query)!!

        assertEquals(emptyList<SuggestedService>(), intent.suggestions)
        verifyStage(LeadServiceIntentService.STAGE_NO_QUOTE)
    }

    /** Pozycja bez cytatu w ogóle — tym bardziej. */
    @Test
    fun `pozycja bez cytatu odpada`() {
        stub(
            families = listOf("CERAMIC_COATING"),
            needs = listOf(
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = true,
                    quote = "zabezpieczenie powierzchni po wykonanej usłudze",
                    services = listOf(svc(4, null, "ANSWER"))
                )
            )
        )

        assertEquals(emptyList<SuggestedService>(), service.intentFor(studioId, leadId, query)!!.suggestions)
        verifyStage(LeadServiceIntentService.STAGE_NO_QUOTE)
    }

    /** Dosprzedaż nie jest odpowiedzią na pytanie i nie wchodzi do wyceny. */
    @Test
    fun `pozycja oznaczona jako dosprzedaz nie wchodzi do wyceny`() {
        stub(
            families = listOf("PPF"),
            needs = listOf(
                need(
                    "APPLY_FILM", "LAMPS", "FULL", "MATCHED", main = true,
                    quote = "kompleksowa renowacja lamp",
                    services = listOf(svc(1, "kompleksowa renowacja lamp", "UPSELL"))
                )
            )
        )

        assertEquals(emptyList<SuggestedService>(), service.intentFor(studioId, leadId, query)!!.suggestions)
        verifyStage(LeadServiceIntentService.STAGE_ROLE_NOT_ANSWER)
    }

    /**
     * Bramka rodzin — ta, którą podsunęły dane z produkcji. Model deklaruje rodzinę
     * CORRECTION_POLISH i wskazuje pozycję z rodziny PPF: zaprzecza sam sobie, a do v3
     * nikt tego nie sprawdzał.
     */
    @Test
    fun `pozycja z rodziny spoza zadeklarowanych odpada`() {
        stub(
            families = listOf("CORRECTION_POLISH"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "MATCHED", main = true,
                    quote = "usunięcie zmatowienia i zarysowań",
                    services = listOf(svc(1, "usunięcie zmatowienia i zarysowań", "ANSWER"))
                )
            )
        )

        assertEquals(emptyList<SuggestedService>(), service.intentFor(studioId, leadId, query)!!.suggestions)
        verifyStage(LeadServiceIntentService.STAGE_FAMILY_MISMATCH)
    }

    /** Osie: polerowanie szyby to nie renowacja lampy, choć obie to CORRECT. */
    @Test
    fun `pozycja o innej czesci auta odpada na osiach`() {
        stub(
            families = listOf("GLASS"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "MATCHED", main = true,
                    quote = "usunięcie zmatowienia i zarysowań",
                    services = listOf(svc(3, "usunięcie zmatowienia i zarysowań", "ANSWER"))
                )
            )
        )

        assertEquals(emptyList<SuggestedService>(), service.intentFor(studioId, leadId, query)!!.suggestions)
        verifyStage(LeadServiceIntentService.STAGE_AXIS_MISMATCH)
    }

    /** Werdykt weryfikatora jest wiążący, nawet gdy wszystkie bramki kodu przeszły. */
    @Test
    fun `weryfikator odrzuca pozycje mimo przejscia bramek kodu`() {
        every { verifier.verify(any(), any()) } returns mapOf(1 to SuggestionVerdict(false, "to inna robota"))
        stubCeramicMatch()

        assertEquals(emptyList<SuggestedService>(), service.intentFor(studioId, leadId, query)!!.suggestions)
        verifyStage(LeadServiceIntentService.STAGE_VERIFIER_REJECTED)
    }

    /**
     * Weryfikator PODPIĘTY, ale przerwany awarią, to abstencja — nie przepuszczenie.
     * Odwrotna decyzja oznaczałaby, że timeout drugiego modelu przywraca stary,
     * wadliwy tryb działania sekcji.
     */
    @Test
    fun `awaria podpietego weryfikatora wstrzymuje pozycje`() {
        every { verifier.verify(any(), any()) } returns null
        stubCeramicMatch()

        assertEquals(emptyList<SuggestedService>(), service.intentFor(studioId, leadId, query)!!.suggestions)
        verifyStage(LeadServiceIntentService.STAGE_VERIFIER_UNAVAILABLE)
    }

    // ═══ CO MA PRZECHODZIĆ ═══════════════════════════════════════════════════════

    /**
     * Przypadek pozytywny: klient pyta wprost o powłokę ceramiczną, studio ją ma,
     * cytat stoi w mailu. Bez tego testu zestaw powyżej byłby spełniony przez kod,
     * który po prostu nigdy niczego nie sugeruje.
     */
    @Test
    fun `robota faktycznie zamowiona i obecna w cenniku przechodzi z cytatem`() {
        val ask = "Dzień dobry, chciałbym zabezpieczyć lakier powłoką ceramiczną, wariant standard. Ile to kosztuje?"
        stub(
            families = listOf("CERAMIC_COATING"),
            needs = listOf(
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = true,
                    quote = "zabezpieczyć lakier powłoką ceramiczną",
                    services = listOf(svc(4, "zabezpieczyć lakier powłoką ceramiczną", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, ask)!!

        assertEquals(ServiceIntentStatus.MATCHED, intent.status)
        assertEquals(1, intent.suggestions!!.size)
        assertEquals("zabezpieczyć lakier powłoką ceramiczną", intent.suggestions!!.single().quote)
        assertEquals(220000L, intent.anchorGross, "Kotwica to cena BRUTTO z cennika, przepisana bez przeliczania")
    }

    /** Dwie faktycznie osobne roboty to dwie potrzeby i dwie pozycje. */
    @Test
    fun `dwie osobne roboty daja dwie pozycje`() {
        val ask = "Poproszę o wycenę: oklejenie reflektorów folią ochronną oraz " +
            "zabezpieczyć lakier powłoką ceramiczną wariant standard."
        stub(
            families = listOf("PPF", "CERAMIC_COATING"),
            needs = listOf(
                need(
                    "APPLY_FILM", "LAMPS", "PARTIAL", "MATCHED", main = true,
                    quote = "oklejenie reflektorów folią ochronną",
                    services = listOf(svc(1, "oklejenie reflektorów folią ochronną", "ANSWER"))
                ),
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = false,
                    quote = "zabezpieczyć lakier powłoką ceramiczną",
                    services = listOf(svc(4, "zabezpieczyć lakier powłoką ceramiczną", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, ask)!!

        assertEquals(2, intent.suggestions!!.size)
        assertEquals(250000L, intent.anchorGross, "30000 + 220000 gr, suma brutto z cennika")
    }

    /**
     * Lead z dwiema robotami, gdzie GŁÓWNEJ nie ma w cenniku, a poboczna jest.
     *
     * Granica jest tu cienka i pilnuje jej ten test: globalny status ma być
     * NOT_IN_CATALOG (więc „Podobne zlecenia" nie pokażą pasma cen dla roboty, której
     * nie wykonujemy, i nie powstanie kotwica), ALE uzasadniona sugestia tej drugiej
     * roboty ma przejść. Skasowanie jej byłoby nadkorektą w drugą stronę: klient
     * poprosił o mycie wprost, a właściciel musiałby je dopisywać z ręki.
     */
    @Test
    fun `poboczna robota z cennika przechodzi, choc glownej nie mamy`() {
        val ask = "Dzień dobry, czy robicie renowację reflektorów? Przy okazji chciałbym " +
            "zabezpieczyć lakier powłoką ceramiczną wariant standard."
        stub(
            families = listOf("CORRECTION_POLISH", "CERAMIC_COATING"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "NOT_IN_CATALOG", main = true,
                    quote = "czy robicie renowację reflektorów", services = emptyList()
                ),
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = false,
                    quote = "zabezpieczyć lakier powłoką ceramiczną",
                    services = listOf(svc(4, "zabezpieczyć lakier powłoką ceramiczną", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, ask)!!

        assertEquals(
            ServiceIntentStatus.NOT_IN_CATALOG, intent.status,
            "Robota główna rządzi werdyktem — pasma cen dla renowacji lamp nie pokażemy"
        )
        assertNull(intent.anchorGross, "Bez kotwicy: kotwica opisuje robotę główną")
        assertEquals(1, intent.suggestions!!.size, "Ale powłoka jest zamówiona wprost i ma zostać podsunięta")
        assertEquals("zabezpieczyć lakier powłoką ceramiczną", intent.suggestions!!.single().quote)
    }

    /** Ta sama pozycja podpięta pod dwie potrzeby to jedna pozycja w wycenie. */
    @Test
    fun `ta sama pozycja pod dwiema potrzebami wchodzi raz`() {
        val ask = "Proszę o wycenę: zabezpieczyć lakier powłoką ceramiczną i zabezpieczenie powierzchni po wykonanej usłudze."
        stub(
            families = listOf("CERAMIC_COATING"),
            needs = listOf(
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = true,
                    quote = "zabezpieczyć lakier powłoką ceramiczną",
                    services = listOf(svc(4, "zabezpieczyć lakier powłoką ceramiczną", "ANSWER"))
                ),
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = false,
                    quote = "zabezpieczenie powierzchni po wykonanej usłudze",
                    services = listOf(svc(4, "zabezpieczenie powierzchni po wykonanej usłudze", "ANSWER"))
                )
            )
        )

        assertEquals(1, service.intentFor(studioId, leadId, ask)!!.suggestions!!.size)
        verifyStage(LeadServiceIntentService.STAGE_DUPLICATE)
    }

    /**
     * Dziennik ma zapisać KAŻDEGO kandydata, także odrzuconego — to on zamienia
     * następne takie śledztwo w SELECT.
     */
    @Test
    fun `dziennik zapisuje takze pozycje odrzucone`() {
        stub(
            families = listOf("CORRECTION_POLISH"),
            needs = listOf(
                need(
                    "CORRECT", "LAMPS", "FULL", "MATCHED", main = true,
                    quote = "usunięcie zmatowienia i zarysowań",
                    services = listOf(svc(1, "usunięcie zmatowienia i zarysowań", "ANSWER"))
                )
            )
        )

        service.intentFor(studioId, leadId, query)

        val saved = slotOfDecisions()
        assertEquals(1, saved.size)
        assertEquals("Oklejenie reflektorów folią ochronną", saved.single().serviceName)
        assertEquals(LeadServiceIntentService.STAGE_FAMILY_MISMATCH, saved.single().stage)
        assertEquals("usunięcie zmatowienia i zarysowań", saved.single().quote)
    }

    /**
     * Potrzeba nie do odczytania (same UNKNOWN, bez cytatu) nie może PRZESUNĄĆ
     * numeracji pozostałych.
     *
     * Pozycje cennika są podpięte numerem potrzeby, więc lista potrzeb z dziurą po
     * odrzuconej sprawdzałaby każdego kolejnego kandydata przy sąsiedniej potrzebie —
     * ten sam kształt pomyłki co „drzwi zamiast fotela", tylko o jeden indeks.
     * Tu potrzeba nr 0 jest nieczytelna, a powłoka pod potrzebą nr 1 ma przejść.
     */
    @Test
    fun `nieczytelna potrzeba nie przesuwa numeracji pozostalych`() {
        val ask = "Dzień dobry. Chciałbym zabezpieczyć lakier powłoką ceramiczną wariant standard."
        stub(
            families = listOf("CERAMIC_COATING"),
            needs = listOf(
                need("UNKNOWN", "UNKNOWN", "UNKNOWN", "MATCHED", main = false, quote = null, services = emptyList()),
                need(
                    "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = true,
                    quote = "zabezpieczyć lakier powłoką ceramiczną",
                    services = listOf(svc(4, "zabezpieczyć lakier powłoką ceramiczną", "ANSWER"))
                )
            )
        )

        val intent = service.intentFor(studioId, leadId, ask)!!

        assertEquals(
            1, intent.suggestions!!.size,
            "Powłoka jest podpięta pod potrzebę nr 1 i ma być sprawdzana przy TEJ potrzebie"
        )
        assertEquals(ServiceIntentStatus.MATCHED, intent.status)
    }

    /** Wersja promptu podbita — inaczej poprawka nie dotarłaby do leadów z zapisanym werdyktem. */
    @Test
    fun `wersja promptu jest podbita ponad v2`() {
        assertEquals("v3", LeadServiceIntentService.PROMPT_VERSION)
    }

    // ═══ narzędzia ═══════════════════════════════════════════════════════════════

    private fun stubCeramicMatch() = stub(
        families = listOf("CERAMIC_COATING"),
        needs = listOf(
            need(
                "PROTECT", "UNKNOWN", "FULL", "MATCHED", main = true,
                quote = "zabezpieczenie powierzchni po wykonanej usłudze",
                services = listOf(svc(4, "zabezpieczenie powierzchni po wykonanej usłudze", "ANSWER"))
            )
        )
    )

    private fun stub(
        needs: List<LeadServiceIntentService.RawNeed>,
        families: List<String>,
        reasoning: String? = null
    ) {
        every { callSpec.entity(LeadServiceIntentService.RawIntent::class.java) } returns
            LeadServiceIntentService.RawIntent(
                intent = null, matchedServices = null, families = families, scope = "PARTIAL",
                reasoning = reasoning, evidenceQuote = null, needs = needs
            )
    }

    private fun need(
        operation: String,
        part: String,
        scope: String,
        status: String,
        main: Boolean,
        quote: String?,
        services: List<LeadServiceIntentService.RawServiceRef>
    ) = LeadServiceIntentService.RawNeed(operation, part, scope, status, main, quote, services)

    private fun svc(number: Int, quote: String?, role: String) =
        LeadServiceIntentService.RawServiceRef(number, quote, role)

    private fun slotOfDecisions(): List<LeadSuggestionDecisionEntity> {
        val slot = mutableListOf<List<LeadSuggestionDecisionEntity>>()
        verify { decisionRepository.saveAll(capture(slot)) }
        return slot.last()
    }

    private fun verifyStage(stage: String) {
        assertTrue(
            slotOfDecisions().any { it.stage == stage },
            "Dziennik ma nazwać bramkę, na której pozycja odpadła — oczekiwano $stage, " +
                "a zapisano ${slotOfDecisions().map { it.stage }}"
        )
    }

    private fun axis(
        name: String,
        gross: Long,
        family: ServiceFamily,
        operation: ServiceOperation,
        part: ServicePart
    ): Pair<ServiceEntity, ClassifiedServiceName> {
        val entity = mockk<ServiceEntity> {
            every { this@mockk.name } returns name
            every { this@mockk.id } returns UUID.randomUUID()
            every { this@mockk.isActive } returns true
            every { this@mockk.isPackage } returns false
            every { this@mockk.basePriceGross } returns gross
            every { this@mockk.requireManualPrice } returns false
        }
        return entity to ClassifiedServiceName(
            nameKey = serviceNameKey(name),
            family = family,
            scope = ServiceScope.UNKNOWN,
            operation = operation,
            part = part
        )
    }
}
