package pl.detailing.crm.rolepreview

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Component
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Piaskownica podglądu roli działa w tym samym procesie co prawdziwe studia, z tymi samymi
 * kluczami do SMSAPI, poczty, KSeF, Przelewy24, GUS, Meta i OpenAI. Nic z niej nie wychodzi
 * na zewnątrz tylko dlatego, że każda integracja pyta [RolePreviewOutboundGuard].
 *
 * Ten test zamienia „każda" w warunek builda: każdy komponent, który dostaje klienta usługi
 * zewnętrznej, musi być tu sklasyfikowany - sam pyta bezpiecznika, jest wołany wyłącznie
 * przez klasy, które pytają, albo nigdy nie działa w imieniu piaskownicy (z uzasadnieniem).
 * Nowa integracja bez decyzji nie przejdzie. Klasyfikacje „sam pyta" i „wołają go" są
 * sprawdzane mechanicznie: wskazana klasa musi wstrzykiwać bezpiecznik.
 *
 * Czego test NIE widzi: nowego wywołania istniejącej, już sklasyfikowanej usługi z nowego
 * miejsca. Dlatego klasyfikacja „wołają go" wymienia wołających z nazwy - dopisanie
 * kolejnego wołającego to moment, w którym trzeba tu zajrzeć.
 */
class OutboundIntegrationSurfaceTest {

    private sealed interface Coverage

    /** Komponent sam pyta bezpiecznika. */
    private data object Self : Coverage

    /** Komponent jest wołany wyłącznie z tych klas, a każda z nich pyta bezpiecznika. */
    private data class Callers(val names: List<String>, val why: String) : Coverage

    /** Komponent nigdy nie działa w imieniu piaskownicy. */
    private data class NeverForSandbox(val why: String) : Coverage

    private fun callers(vararg names: String, why: String) = Callers(names.toList(), why)

    /**
     * Klienci usług zewnętrznych, którzy NIE pytają bezpiecznika sami - każdy, kto ich
     * dostaje, musi być sklasyfikowany w [coverage].
     */
    private val outboundTypes: List<Class<*>> = listOfNotNull(
        pl.detailing.crm.email.provider.EmailProvider::class.java,
        pl.detailing.crm.smscampaigns.provider.SmsProvider::class.java,
        pl.detailing.crm.payments.p24.Przelewy24Client::class.java,
        pl.detailing.crm.gus.port.CompanyDataProvider::class.java,
        pl.detailing.crm.instagram.infrastructure.InstagramDataProvider::class.java,
        pl.detailing.crm.instagram.ads.MetaAdLibraryClient::class.java,
        pl.detailing.crm.instagram.ads.AdvertiserInstagramResolver::class.java,
        pl.detailing.crm.instagram.ads.discovery.ig.MetaIgResolverClient::class.java,
        pl.detailing.crm.mailbox.infrastructure.MailAutodiscoverService::class.java,
        pl.detailing.crm.product.adapter.web.OpenAiWebSearchClient::class.java,
        org.springframework.ai.chat.client.ChatClient::class.java,
        org.springframework.ai.chat.model.ChatModel::class.java,
        org.springframework.ai.embedding.EmbeddingModel::class.java,
        org.springframework.ai.vectorstore.VectorStore::class.java,
        org.springframework.ai.openai.OpenAiAudioTranscriptionModel::class.java,
        org.springframework.web.client.RestTemplate::class.java,
        java.net.http.HttpClient::class.java,
        org.springframework.mail.javamail.JavaMailSender::class.java,
        classOrNull("org.springframework.web.client.RestClient"),
        classOrNull("org.springframework.web.reactive.function.client.WebClient")
    )

    /**
     * Klienci, którzy pytają bezpiecznika sami, w miejscu, przez które przechodzi każde ich
     * żądanie - ich użytkowników test nie klasyfikuje, sprawdza za to samego klienta.
     */
    private val selfGuardedClients: List<Class<*>> = listOf(
        pl.detailing.crm.communication.OutboundCommunicationGateway::class.java,
        pl.detailing.crm.comms.send.AccountMailSender::class.java,
        pl.detailing.crm.comms.engine.ImapSessions::class.java,
        pl.detailing.crm.push.send.WebPushSender::class.java
    )

    private val coverage: Map<String, Coverage> = mapOf(
        // ── E-mail i SMS (wiadomości do klientów idą przez OutboundCommunicationGateway) ──
        "RequestPasswordResetHandler" to Self,
        "ProvisionEmployeeAccountHandler" to Self,
        "ResendEmployeeInvitationHandler" to Self,
        "ReportProblemService" to Self,
        "SmsAuthorizationNotifier" to Self,
        "MetaPageChangeRequestMailer" to Self,

        // ── Płatności, GUS, poczta studia ───────────────────────────────────────
        "CheckoutService" to Self,
        "Przelewy24WebhookController" to NeverForSandbox(
            "powiadomienia Przelewy24 o zamówieniach, których piaskownica nie założy - CheckoutService jej odmawia"
        ),
        "GusConfig.gusCompanyService" to Self,
        "GusConfig.gusRawSoapClient" to callers(
            "GusConfig.gusCompanyService",
            why = "klient SOAP GUS; jedyna droga do niego to GusCompanyService"
        ),
        "MailAccountService" to Self,

        // ── Instagram i Meta ─────────────────────────────────────────────────────
        "SuggestionService" to NeverForSandbox(
            "synchronizacja profili obserwowanych przez studia; piaskownica nie obserwuje profili " +
                "(AddInstagramProfileHandler jej odmawia, seed pomija profile)"
        ),
        "InstagramSyncService" to NeverForSandbox("jak SuggestionService - tylko profile obserwowane"),
        "InstagramProfileDetailsSyncService" to NeverForSandbox("jak SuggestionService - tylko profile obserwowane"),
        "MetaAdsReadService" to callers(
            "MetaAdsController",
            why = "wyszukanie i podpięcie strony idą wyłącznie przez kontroler, który pyta bezpiecznika"
        ),
        "MetaAdsSyncService" to NeverForSandbox(
            "odświeża reklamy stron podpiętych do profili; piaskownica strony nie podepnie (MetaAdsController)"
        ),
        "AdDiscoveryReadService" to Self,
        "AdDiscoveryFetchService" to callers(
            "AdDiscoveryReadService", "AdDiscoveryScheduler",
            why = "pobiera reklamy na żądanie odczytu albo z harmonogramu - oba pomijają piaskownice"
        ),
        "MetaIgLookupService" to callers(
            "AdDiscoveryReadService",
            why = "ustala profil IG strony reklamodawcy przy odczycie; piaskownica tego kroku nie wykonuje"
        ),
        "MetaIgBackfillScheduler" to NeverForSandbox("uzupełnia wspólny rejestr stron reklamodawców, bez studia"),
        "MetaIgCanary" to NeverForSandbox("sprawdza dostępność usługi Meta, bez studia"),

        // ── AI: Instagram ────────────────────────────────────────────────────────
        "InstagramPostGeneratorService" to callers(
            "InstagramPostGenerationController",
            why = "generowanie postów wyłącznie z kontrolera, który pyta bezpiecznika"
        ),
        "InstagramPostVerifierService" to callers(
            "InstagramPostGenerationController",
            why = "sprawdza szkic w trakcie generowania (InstagramPostGeneratorService)"
        ),
        "InstagramInspirationService" to callers(
            "InstagramPostGenerationController", "InstagramPostIndexingService", "GeneratedPostVectorIndexer",
            why = "przykłady do generowania i indeksowania - wszyscy wołający pomijają piaskownice"
        ),
        "InstagramPostClassificationService" to callers(
            "InstagramPostIndexingService", "GeneratedPostVectorIndexer",
            why = "klasyfikuje posty przy indeksowaniu"
        ),
        "InstagramPostIndexingService" to Self,
        "GeneratedPostVectorIndexer" to Self,
        "WeeklyDigestService" to Self,

        // ── AI: leady ────────────────────────────────────────────────────────────
        "LeadServiceIntentService" to callers(
            "LeadsController", "LeadSimilarPrecomputeListener",
            why = "podobne wizyty i sugestie usług: odczyt i odświeżenie w kontrolerze, przeliczenie po rozpoznaniu auta"
        ),
        "LeadSuggestionVerifier" to callers(
            "LeadsController", "LeadSimilarPrecomputeListener",
            why = "część LeadServiceIntentService"
        ),
        "AnchorVerifier" to callers(
            "LeadsController", "LeadSimilarPrecomputeListener",
            why = "część LeadServiceIntentService i SimilarVisitsHandler"
        ),
        "LeadAttachmentVisionService" to callers(
            "LeadSimilarPrecomputeListener",
            why = "odczyt zdjęć leada przy przeliczaniu podobnych wizyt"
        ),
        "LeadTagSuggestionService" to callers(
            "LeadAutoTagListener",
            why = "tagi leada po jego utworzeniu; druga ścieżka to klasyfikacja poczty, której piaskownica nie ma"
        ),
        "LeadVehicleExtractionService" to callers(
            "LeadVehicleExtractionListener",
            why = "rozpoznanie auta z treści leada"
        ),
        "LeadMessageClassifier" to NeverForSandbox(
            "klasyfikuje maile ze skrzynki studia; piaskownica nie podłączy skrzynki (MailAccountService, ImapSessions)"
        ),
        "FormMailExtractionService" to NeverForSandbox("czyta maile formularzy ze skrzynki studia - jak LeadMessageClassifier"),

        // ── AI: pozostałe ────────────────────────────────────────────────────────
        "ServiceFamilyClassifier" to Self,
        "VehicleCatalogMatcher" to callers(
            "HandleFormSubmissionHandler", "LeadVehicleExtractionListener",
            why = "marka i model z formularza albo z treści leada; trzecia ścieżka to poczta formularzy, której piaskownica nie ma"
        ),
        "VehicleSegmentService" to callers(
            "LeadsController", "LeadSimilarPrecomputeListener", "LeadVehicleExtractionListener",
            why = "segment auta dla podobnych wizyt; indeksowanie wizyt (VisitSimilarityIndexer) pomija piaskownice w zapytaniu"
        ),
        "SmsContentGeneratorService" to callers(
            "GenerateSmsContentHandler",
            why = "treść SMS-a na żądanie użytkownika"
        ),
        "MailProofreadService" to callers("CommsController", why = "korekta treści maila na żądanie użytkownika"),
        "VinExtractionService" to callers("BatchOrderController", why = "odczyt VIN ze zdjęcia na żądanie użytkownika"),
        "BarcodeImageExtractionService" to callers(
            "ProductController", "MobileProductScanController",
            why = "odczyt kodu kreskowego ze zdjęcia z komputera albo z telefonu"
        ),
        "OpenAiTranscriptionService" to callers(
            "TasksController", "MobileTokenService",
            why = "zadanie z nagrania w CRM; aplikacja mobilna działa na tokenie, którego konto piaskownicy nie dostaje"
        ),
        "WebProductProvider" to callers(
            "ProductController",
            why = "wyszukanie produktu w sieci przy rozpoznaniu kodu; piaskownica szuka tylko w katalogu"
        )
    )

    @Test
    fun `kazda integracja zewnetrzna jest za bezpiecznikiem piaskownicy albo swiadomie poza nim`() {
        val units = scanUnits()
        val unitsByName = units.associateBy { it.name }
        val problems = mutableListOf<String>()

        val dependents = units.filter { unit ->
            !unit.isOutboundClient && unit.dependencies.any { dependency -> outboundTypes.any { it.isAssignableFrom(dependency) } }
        }
        dependents.forEach { unit ->
            val outbound = unit.dependencies.filter { dependency -> outboundTypes.any { it.isAssignableFrom(dependency) } }
                .map { it.simpleName }.distinct()
            when (val entry = coverage[unit.name]) {
                null -> problems += "${unit.name} (${outbound.joinToString()}) - brak klasyfikacji"
                Self -> if (!unit.injectsGuard) {
                    problems += "${unit.name} - sklasyfikowany jako „sam pyta bezpiecznika”, a go nie wstrzykuje"
                }
                is Callers -> entry.names.forEach { caller ->
                    val callerUnit = unitsByName[caller]
                    when {
                        callerUnit == null -> problems += "${unit.name} - wołający $caller nie istnieje"
                        !callerUnit.injectsGuard -> problems += "${unit.name} - wołający $caller nie pyta bezpiecznika"
                    }
                }
                is NeverForSandbox -> Unit
            }
        }

        val stale = coverage.keys - dependents.map { it.name }.toSet()
        if (stale.isNotEmpty()) problems += "Nieaktualne wpisy (komponent nie korzysta już z integracji): $stale"

        selfGuardedClients.forEach { client ->
            val unit = unitsByName[client.simpleName]
            if (unit == null || !unit.injectsGuard) {
                problems += "${client.simpleName} - klient uznany za pilnującego się sam nie wstrzykuje bezpiecznika"
            }
        }
        // Klient KSeF jest beanem z SDK - bezpiecznik dostaje fabryka, która go opakowuje.
        val ksefFactory = unitsByName["KsefClientConfig.ksefClient"]
        if (ksefFactory == null || !ksefFactory.injectsGuard) {
            problems += "KsefClientConfig.ksefClient - klient KSeF musi być opakowany bezpiecznikiem"
        }

        if (problems.isNotEmpty()) {
            fail<Unit>(
                "Integracje zewnętrzne bez decyzji o piaskownicy podglądu roli:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\n\nKażda integracja musi pytać RolePreviewOutboundGuard (albo być wołana wyłącznie " +
                    "przez klasy, które pytają) - inaczej piaskownica wyśle coś prawdziwemu klientowi."
            )
        }
    }

    // ── Skan ──────────────────────────────────────────────────────────────────────

    /** Komponent albo metoda @Bean - jednostka, która dostaje zależności od Springa. */
    private data class Unit(
        val name: String,
        val dependencies: List<Class<*>>,
        val injectsGuard: Boolean,
        val isOutboundClient: Boolean
    )

    private fun scanUnits(): List<Unit> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Component::class.java))
        val classes = scanner.findCandidateComponents("pl.detailing.crm")
            .mapNotNull { it.beanClassName }
            // Komponenty-atrapy z testów nie są częścią aplikacji.
            .filterNot { it.contains("Test") }
            .map { Class.forName(it) }

        val components = classes.map { type ->
            val constructor = type.declaredConstructors.filterNot { it.isSynthetic }.maxByOrNull { it.parameterCount }
            val injected = constructor?.genericParameterTypes.orEmpty().toList() +
                type.declaredFields.filter { it.isAnnotationPresent(Autowired::class.java) }.map { it.genericType }
            unitOf(type.simpleName, injected, isOutboundClient = isOutboundClient(type))
        }
        val beanMethods = classes
            .filter { it.isAnnotationPresent(Configuration::class.java) }
            .flatMap { config ->
                config.declaredMethods.filter { it.isAnnotationPresent(Bean::class.java) }.map { method ->
                    // Fabryka budująca samego klienta (np. ChatClient z buildera) nie jest jego użytkownikiem.
                    unitOf("${config.simpleName}.${method.name}", method.genericParameterTypes.toList(), isOutboundClient(method.returnType))
                }
            }
        return components + beanMethods
    }

    private fun unitOf(name: String, injected: List<Type>, isOutboundClient: Boolean): Unit {
        val dependencies = injected.flatMap { rawTypes(it) }
        return Unit(
            name = name,
            dependencies = dependencies,
            injectsGuard = dependencies.any { it == RolePreviewOutboundGuard::class.java || it == RolePreviewStudios::class.java },
            isOutboundClient = isOutboundClient
        )
    }

    private fun isOutboundClient(type: Class<*>): Boolean =
        (outboundTypes + selfGuardedClients).any { it.isAssignableFrom(type) } ||
            type.name == "pl.akmf.ksef.sdk.client.interfaces.KSeFClient"

    /** Typ i - jeden poziom w głąb - argumenty generyczne (ObjectProvider<ChatClient>, List<EmailProvider>). */
    private fun rawTypes(type: Type): List<Class<*>> = when (type) {
        is Class<*> -> listOf(type)
        is ParameterizedType -> listOfNotNull(type.rawType as? Class<*>) +
            type.actualTypeArguments.mapNotNull { it as? Class<*> ?: (it as? ParameterizedType)?.rawType as? Class<*> }
        else -> emptyList()
    }

    private companion object {
        fun classOrNull(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()
    }
}
