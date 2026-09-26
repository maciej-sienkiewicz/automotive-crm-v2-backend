package pl.detailing.crm.careinstruction

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionRepository
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Instant
import java.util.UUID

/**
 * Zasiewa słownik instrukcji pielęgnacyjnych przy pierwszym kontakcie studia z modułem.
 *
 * Domyślne wpisy to instrukcje przypisane do rodzaju usługi (ceramika, folia PPF,
 * wnętrze) — zwykłe wiersze słownika: studio może je poprawić własnym językiem,
 * przypiąć do swoich usług albo skasować.
 *
 * Skasowanie musi być TRWAŁE, więc zasiew ma znacznik w ustawieniach studia
 * (`care_instructions_seeded_at`). Bez niego pusty słownik przy każdym starcie
 * aplikacji wyglądałby jak studio, które jeszcze nie dostało domyślnych wpisów —
 * i dostawałoby je z powrotem w kółko.
 */
@Service
class DefaultCareInstructionProvisioner(
    private val repository: CareInstructionRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val linkRepository: ServiceCareInstructionRepository,
    private val serviceRepository: ServiceRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Instrukcje, po które klient naprawdę wraca po odbiorze auta. Wcześniej były tu
         * cztery ogólniki (dwa wiadra, mikrofibra, ptasie odchody, pH), które klient zna
         * bez certyfikatu; zastąpiła je migracja V166.
         *
         * Żadna nie jest zaznaczana przy każdym certyfikacie ([CareInstructionEntity.isDefaultSelected]
         * = false): każda dotyczy innej usługi, a porada o folii PPF na certyfikacie prania
         * tapicerki byłaby szumem. Instrukcję zaznacza wybranie usługi, do której studio ją
         * przypięło. Treść musi się zgadzać z V166 (CareInstructionDefaultsMigrationTest).
         *
         * Każda zasada zaczyna się od tego, CO robić, a nie od zakazu. Sekcja nosi tytuł
         * „Jak utrzymać efekt" i ma się tak czytać: instrukcja dbania o wynik pracy,
         * nie regulamin z listą przewinień.
         */
        val DEFAULTS: List<DefaultInstruction> = listOf(
            DefaultInstruction(
                "Myjnia bezdotykowa a powłoka ceramiczna",
                "Myjnia bezdotykowa jest dla powłoki ceramicznej bezpieczna, bo nic nie dotyka lakieru. " +
                "Pierwsze mycie zrób najwcześniej 7 dni po aplikacji, kiedy powłoka się utwardzi. " +
                "Wybieraj program bez wosku i nabłyszczacza: wosk przykrywa powłokę i odbiera jej efekt odpychania wody. " +
                "Aktywną pianę nakładaj na chłodny lakier, nie w pełnym słońcu, i spłucz ją, zanim zaschnie. " +
                "Mocna chemia myjni stosowana co tydzień skraca życie powłoki, dlatego co któreś mycie zrób ręcznie szamponem o neutralnym pH.",
                listOf("ceram")
            ),
            DefaultInstruction(
                "Folia PPF na myjni bezdotykowej",
                "Pierwsze mycie zrób najwcześniej 7 dni po oklejeniu, kiedy klej folii zwiąże z lakierem. " +
                "Trzymaj lancę co najmniej 30 cm od auta, a przy krawędziach folii dalej, około 50 cm. " +
                "Kieruj strumień prostopadle do powierzchni albo wzdłuż krawędzi, od środka folii na zewnątrz. " +
                "Nigdy nie celuj pod krawędź: woda pod ciśnieniem wchodzi pod folię i ją podrywa. " +
                "Nie używaj dyszy rotacyjnej na oklejonych elementach. " +
                "Na folii matowej wybieraj program bez wosku, bo wosk zostawia na niej błyszczące plamy.",
                listOf("ppf")
            ),
            DefaultInstruction(
                "Kosmetyki do wnętrza, których unikać",
                "Do każdego materiału używaj środka przeznaczonego właśnie do niego. " +
                "Na ekranach i szybkach zegarów nie stosuj płynów z amoniakiem ani alkoholem, bo niszczą powłokę antyrefleksyjną. " +
                "Skóry nie czyść uniwersalnymi odtłuszczaczami ani płynem do naczyń: wysuszają ją i zmywają barwnik. " +
                "Kokpitu nie nabłyszczaj środkami z silikonem, które dają odblaski na szybie i przyciągają kurz. " +
                "Tapicerki nie czyść wybielaczem ani środkami z chlorem, a plastików i skóry chusteczkami do mebli.",
                listOf("wnętrz", "wnetrz", "tapicer", "skór", "skor")
            )
        )
    }

    /** @return true, gdy w tym wywołaniu doszło do zasiewu. */
    @Transactional
    fun ensureDefaults(studioId: StudioId): Boolean {
        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
            ?: StudioSettingsEntity(studioId = studioId.value, updatedAt = Instant.now())

        if (settings.careInstructionsSeededAt != null) return false

        // Pas bezpieczeństwa dla studiów sprzed znacznika: jeśli słownik już coś ma,
        // znaczy to, że ktoś go wypełnił sam — dokładanie domyślnych byłoby bałaganem.
        if (repository.countByStudioId(studioId.value) > 0L) {
            settings.careInstructionsSeededAt = Instant.now()
            studioSettingsRepository.save(settings)
            return false
        }

        val now = Instant.now()
        // Przypięcie po nazwie usługi, tak samo jak w V166: instrukcja o folii PPF sama
        // zaznacza się przy usłudze z „PPF" w nazwie. Studio poprawia to w cenniku.
        val services = serviceRepository.findActiveByStudioId(studioId.value)
        DEFAULTS.forEachIndexed { index, default ->
            val instruction = repository.save(
                CareInstructionEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    title = default.title,
                    content = default.content,
                    isDefaultSelected = false,
                    sortOrder = index,
                    createdAt = now,
                    updatedAt = now
                )
            )
            services.filter { default.matches(it.name) }.forEach { service ->
                linkRepository.save(
                    ServiceCareInstructionEntity(
                        id = UUID.randomUUID(),
                        studioId = studioId.value,
                        serviceId = service.id,
                        careInstructionId = instruction.id,
                        createdAt = now
                    )
                )
            }
        }

        settings.careInstructionsSeededAt = now
        studioSettingsRepository.save(settings)
        logger.info("Seeded {} default care instructions for studio {}", DEFAULTS.size, studioId.value)
        return true
    }
}

/**
 * Domyślna instrukcja słownika. [serviceKeywords] to fragmenty nazwy usługi (małymi
 * literami), przy których instrukcja przypina się do usługi sama; te same wzorce stoją
 * w migracji V166.
 */
data class DefaultInstruction(
    val title: String,
    val content: String,
    val serviceKeywords: List<String>
) {
    fun matches(serviceName: String): Boolean {
        val name = serviceName.lowercase()
        return serviceKeywords.any { name.contains(it) }
    }
}
