package pl.detailing.crm.instagram.analytics

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicEntity
import pl.detailing.crm.instagram.infrastructure.InstagramPostTopicRepository
import java.time.Instant
import java.util.*

/**
 * Słownik tematów treści dla branży auto detailingu.
 * Etykiety prezentowane użytkownikowi w prostym polskim ([labelPl]).
 */
enum class InstagramPostTopic(val labelPl: String) {
    POWLOKA_CERAMICZNA("Powłoka ceramiczna"),
    FOLIA_PPF("Folia ochronna PPF"),
    KOREKTA_LAKIERU("Korekta i polerowanie"),
    DETAILING_WNETRZA("Detailing wnętrza"),
    FELGI_OPONY("Felgi i opony"),
    OKLEJANIE("Oklejanie i zmiana koloru"),
    MYCIE_PIELEGNACJA("Mycie i pielęgnacja"),
    PRZED_PO("Metamorfoza przed/po"),
    KONKURS("Konkurs"),
    PROMOCJA("Promocja"),
    ZESPOL_BACKSTAGE("Zespół i kulisy pracy"),
    INNE("Inne");

    companion object {
        fun labelFor(value: String?): String =
            entries.firstOrNull { it.name == value }?.labelPl ?: INNE.labelPl
    }
}

data class TopicClassification(
    val topic: InstagramPostTopic,
    val isPromo: Boolean,
    val isContest: Boolean,
    val discountPct: Int?
)

/**
 * Klasyfikacja tematów postów: deterministyczny słownik regexowy (darmowy, powtarzalny,
 * testowalny). Idempotentna – post klasyfikowany raz (tabela instagram_post_topics).
 *
 * Rozszerzenie LLM (instagram.ai.topic-classification.enabled) jest punktem zaczepienia
 * na przyszłość – domyślnie wyłączone, żeby koszt klasyfikacji był zerowy.
 */
@Service
class TopicClassificationService(
    private val topicRepository: InstagramPostTopicRepository,
    @Value("\${instagram.ai.topic-classification.enabled:false}") private val llmEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(TopicClassificationService::class.java)

    /** Klasyfikuje wszystkie jeszcze niesklasyfikowane posty profilu. */
    @Transactional
    fun classifyNewPosts(profileId: UUID): Int {
        val unclassified = topicRepository.findUnclassifiedPosts(profileId)
        if (unclassified.isEmpty()) return 0

        val now = Instant.now()
        val entities = unclassified.map { post ->
            val result = classify(post)
            InstagramPostTopicEntity(
                id = UUID.randomUUID(),
                postId = post.id,
                topic = result.topic.name,
                isPromo = result.isPromo,
                isContest = result.isContest,
                discountPct = result.discountPct,
                method = "REGEX",
                classifiedAt = now
            )
        }
        topicRepository.saveAll(entities)

        val promos = entities.count { it.isPromo }
        if (promos > 0) {
            log.info("Instagram tematy: profil {} – {} nowych postów, w tym {} promocji", profileId, entities.size, promos)
        }
        return entities.size
    }

    fun classify(post: InstagramPostSnapshotEntity): TopicClassification {
        val text = ((post.caption ?: "") + " " + (post.hashtags ?: "")).lowercase()

        val isPromo = PROMO_PATTERNS.any { it.containsMatchIn(text) }
        val isContest = CONTEST_PATTERNS.any { it.containsMatchIn(text) }
        val discountPct = DISCOUNT_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..99 }

        val scores = TOPIC_KEYWORDS.mapValues { (_, patterns) ->
            patterns.count { it.containsMatchIn(text) }
        }
        val best = scores.filterValues { it > 0 }.maxByOrNull { it.value }?.key

        val topic = when {
            isContest -> InstagramPostTopic.KONKURS
            best != null -> best
            isPromo -> InstagramPostTopic.PROMOCJA
            else -> InstagramPostTopic.INNE
        }

        return TopicClassification(topic, isPromo, isContest, discountPct)
    }

    companion object {
        private fun p(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

        private val PROMO_PATTERNS = listOf(
            p("promocj"), p("rabat"), p("zni[żz]k"), p("taniej"), p("wyprzeda[żz]"),
            p("-\\s?\\d{1,2}\\s?%"), p("\\d{1,2}\\s?%\\s?(taniej|zni[żz]ki|rabatu)"),
            p("gratis"), p("okazj"), p("oferta specjaln"), p("(?<![a-ząęółśżźćń])sale(?![a-ząęółśżźćń])"),
            p("last minute"), p("wolne terminy"), p("promka")
        )

        private val CONTEST_PATTERNS = listOf(
            p("konkurs"), p("rozdani"), p("rozdajemy"), p("do wygrania"),
            p("wygraj"), p("giveaway"), p("nagrod")
        )

        private val DISCOUNT_REGEX = Regex("(?:-|do\\s|a[żz]\\s)?(\\d{1,2})\\s?%")

        private val TOPIC_KEYWORDS: Map<InstagramPostTopic, List<Regex>> = mapOf(
            InstagramPostTopic.POWLOKA_CERAMICZNA to listOf(
                p("ceramik"), p("ceramic"), p("pow[łl]ok"), p("coating"), p("kwarcow"), p("9h")
            ),
            InstagramPostTopic.FOLIA_PPF to listOf(
                p("(?<![a-ząęółśżźćń])ppf(?![a-ząęółśżźćń])"), p("paint protection"), p("folia ochronn"),
                p("foli[ąa] ochronn"), p("oklejanie ochronn")
            ),
            InstagramPostTopic.KOREKTA_LAKIERU to listOf(
                p("korekt"), p("poler"), p("polish"), p("rys[y i]"), p("usuwanie rys"),
                p("hologram"), p("one step"), p("cutting")
            ),
            InstagramPostTopic.DETAILING_WNETRZA to listOf(
                p("wn[ęe]trz"), p("tapicerk"), p("interior"), p("pranie tapicerki"),
                p("sk[óo]rzan"), p("alcantar"), p("dezynfekcj"), p("ozonowanie")
            ),
            InstagramPostTopic.FELGI_OPONY to listOf(
                p("felg"), p("opon"), p("wheels"), p("rims"), p("ko[łl]pak")
            ),
            InstagramPostTopic.OKLEJANIE to listOf(
                p("(?<![a-ząęółśżźćń])wrap"), p("oklejanie"), p("zmiana koloru"),
                p("folia barwn"), p("car wrap"), p("przyciemnianie szyb")
            ),
            InstagramPostTopic.MYCIE_PIELEGNACJA to listOf(
                p("mycie"), p("detailing"), p("wosk"), p("pielegnacj"), p("piel[ęe]gnacj"),
                p("auto spa"), p("czyszczenie"), p("renowacj")
            ),
            InstagramPostTopic.PRZED_PO to listOf(
                p("przed[ /]i?\\s?po"), p("before.{0,4}after"), p("metamorfoz"), p("efekt ko[ńn]cowy")
            ),
            InstagramPostTopic.ZESPOL_BACKSTAGE to listOf(
                p("zesp[óo][łl]"), p("ekip"), p("kulisy"), p("backstage"), p("z [żz]ycia studia"),
                p("rekrutacj"), p("praca w")
            )
        )
    }
}
