package pl.detailing.crm.careinstruction

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Instant
import java.util.UUID

/**
 * Zasiewa słownik instrukcji pielęgnacyjnych przy pierwszym kontakcie studia z modułem.
 *
 * Domyślne wpisy to cztery zasady prawdziwe przy każdej realizacji — wcześniej siedziały
 * na sztywno w generatorze PDF. Teraz są zwykłymi wierszami słownika: studio może je
 * poprawić własnym językiem albo skasować.
 *
 * Skasowanie musi być TRWAŁE, więc zasiew ma znacznik w ustawieniach studia
 * (`care_instructions_seeded_at`). Bez niego pusty słownik przy każdym starcie
 * aplikacji wyglądałby jak studio, które jeszcze nie dostało domyślnych wpisów —
 * i dostawałoby je z powrotem w kółko.
 */
@Service
class DefaultCareInstructionProvisioner(
    private val repository: CareInstructionRepository,
    private val studioSettingsRepository: StudioSettingsRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Zasady niezależne od tego, co zrobiono przy aucie.
         *
         * Świadomie nie ma tu terminów utwardzania powłok ani zakazu mycia przez pierwsze
         * dni — to zależy od usługi i wchodzi do słownika jako osobne wpisy przypięte do
         * konkretnych pozycji cennika.
         */
        val DEFAULTS: List<Pair<String, String>> = listOf(
            "Mycie" to "Myj pojazd metodą dwóch wiader, szamponem o neutralnym pH. " +
                "Myjnie automatyczne ze szczotkami zostawiają na lakierze siatkę rys.",
            "Osuszanie" to "Osuszaj miękką mikrofibrą lub sprężonym powietrzem. " +
                "Woda pozostawiona do odparowania zostawia osad z kamienia.",
            "Zabrudzenia organiczne" to "Odchody ptaków, owady i żywicę usuwaj możliwie szybko. " +
                "Zaschnięte wytrawiają lakier i ślad po nich zostaje na stałe.",
            "Chemia" to "Unikaj preparatów silnie alkalicznych i kwaśnych poza zastosowaniem, " +
                "do którego są przeznaczone. Skracają żywotność zabezpieczeń."
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
        DEFAULTS.forEachIndexed { index, (title, content) ->
            repository.save(
                CareInstructionEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    title = title,
                    content = content,
                    isDefaultSelected = true,
                    sortOrder = index,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        settings.careInstructionsSeededAt = now
        studioSettingsRepository.save(settings)
        logger.info("Seeded {} default care instructions for studio {}", DEFAULTS.size, studioId.value)
        return true
    }
}
