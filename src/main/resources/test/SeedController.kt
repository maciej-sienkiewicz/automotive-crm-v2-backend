package com.example.demo.adcopy.controller

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

/**
 * Kontroler do seedowania danych testowych – nagłówki + VectorStore.
 * Dostępny tylko z profilem "default" (brak ograniczenia profile na razie, zawsze aktywny).
 */
@RestController
@RequestMapping("/api/seed")
class SeedController(
    private val jdbcTemplate: JdbcTemplate,
    private val vectorStore: VectorStore
) {
    private val logger = LoggerFactory.getLogger(SeedController::class.java)

    data class SeedResult(val headlinesInserted: Int, val documentsIndexed: Int)

    /**
     * Dane posta: text do embeddingu, pełna treść, metadata.
     */
    data class PostSeed(
        val embeddingText: String,
        val fullContent: String,
        val feedbackStatus: String,
        val userId: Long,
        val postTone: String,
        val postLength: String,
        val serviceType: String,
        val carBrand: String
    )

    /**
     * POST /api/seed/headlines – wstawia przykładowe nagłówki i indeksuje je w VectorStore.
     */
    @PostMapping("/headlines")
    fun seedHeadlines(): SeedResult {
        logger.info("Starting seed of detailing posts...")

        // Wyczyść istniejące dane
        jdbcTemplate.update("DELETE FROM ad_headlines")
        jdbcTemplate.update("DELETE FROM vector_store")
        logger.info("Cleared existing data from ad_headlines and vector_store")

        val posts = listOf(
            // ══════════════════════════════════════════════════════
            // userId=1: studio premium, dużo LIKE w tonie premium
            // ══════════════════════════════════════════════════════

            // LIKE / premium / full / ppf
            PostSeed(
                "Mercedes-AMG GT oklejanie folią PPF ochrona lakieru realizacja premium",
                "Mercedes-AMG GT BRABUS w pełnej ochronie💎\nLakier zabezpieczony folią PPF🛡️\nNawet najmniejszy kamień przy dużej prędkości może zostawić trwały ślad.\nZabezpiecz lakier i jedź bez obaw.\n📍Car Art Detailing",
                "LIKE", 1L, "premium", "full", "ppf", "Mercedes"
            ),
            PostSeed(
                "BMW M4 Competition folia PPF full body zabezpieczenie premium",
                "BMW M4 Competition — pełna ochrona PPF💎\nKażdy element nadwozia pod osłoną folii.\nLakier Isle of Man Green zasługuje na najlepszą ochronę🛡️\n📍Car Art Detailing",
                "LIKE", 1L, "premium", "full", "ppf", "BMW"
            ),
            PostSeed(
                "Porsche 911 GT3 folia PPF maska zderzak progi premium",
                "Porsche 911 GT3 — precyzja ochrony💎\nFolia PPF na masce, zderzaku i progach.\nKażdy detal ma znaczenie.\n📍Car Art Detailing",
                "LIKE", 1L, "premium", "short", "ppf", "Porsche"
            ),
            PostSeed(
                "Audi RS6 folia PPF full body ochrona lakieru premium realizacja",
                "Audi RS6 Avant w pełnej ochronie PPF💎\nNardo Grey + folia PPF = perfekcja.\nLakier chroniony, styl zachowany🛡️\n📍Car Art Detailing",
                "LIKE", 1L, "premium", "short", "ppf", "Audi"
            ),

            // LIKE / premium / full / ceramic
            PostSeed(
                "Mercedes GLE Coupe powłoka ceramiczna ceramic coating premium",
                "Mercedes GLE Coupé — powłoka ceramiczna🔬\nHydrofobowość na lata. Lakier jak lustro.\nEfekt, który widać przy każdym myciu💧\n📍Car Art Detailing",
                "LIKE", 1L, "premium", "full", "ceramic", "Mercedes"
            ),
            PostSeed(
                "BMW X5 powłoka ceramiczna ceramic pro ochrona lakieru",
                "BMW X5 — ceramiczna tarcza ochronna🔬\n3 warstwy Ceramic Pro. Lustrzany połysk.\nLakier odporny na zabrudzenia i UV💧\n📍Car Art Detailing",
                "LIKE", 1L, "premium", "short", "ceramic", "BMW"
            ),

            // LIKE / technical / full / ppf
            PostSeed(
                "folia PPF XPEL Ultimate Plus specyfikacja techniczna grubość samoregeneracja",
                "Folia PPF XPEL Ultimate Plus — fakty🔧\nGrubość: 8.5 mil (215 µm). Samoregeneracja termiczna w 20 min.\nOdporność na odpryski: ASTM D3170. Gwarancja: 10 lat.\nTechnologia, która chroni Twoją inwestycję.\n📍Car Art Detailing",
                "LIKE", 1L, "technical", "full", "ppf", "universal"
            ),
            PostSeed(
                "ceramic coating trwałość twardość 9H test hydrofobowość specyfikacja",
                "Powłoka ceramiczna — co mówią liczby?📊\nTwardość: 9H w skali Mohs. Kąt kontaktowy: >110°.\nTrwałość: 3-5 lat. UV rejection: 99%.\nFakty > marketing.\n📍Car Art Detailing",
                "LIKE", 1L, "technical", "full", "ceramic", "universal"
            ),

            // LIKE / emotional / short / ppf
            PostSeed(
                "samochód pasja ochrona folia PPF emocjonalny storytelling",
                "Ten lakier przeszedł więcej niż myślisz...\nTeraz zasługuje na nowe życie🖤\nFolia PPF pisze nowy rozdział tej historii.\n📍Car Art Detailing",
                "LIKE", 1L, "emotional", "short", "ppf", "universal"
            ),

            // LIKE / casual / short / detailing
            PostSeed(
                "efekt przed po before after detailing mycie casual",
                "Przed ➡️ Po 🤯\nChyba nie trzeba dużo mówić 😏\nUmów się na detailing!\n📍Car Art Detailing",
                "LIKE", 1L, "casual", "short", "detailing", "universal"
            ),

            // DISLIKE / userId=1 — agresywne, clickbait
            PostSeed(
                "MEGA PROMOCJA PPF najtaniej oklejanie kup teraz",
                "MEGA PROMOCJA!!! FOLIA PPF ZA PÓŁ CENY!!! KUP TERAZ!!!",
                "DISLIKE", 1L, "casual", "short", "ppf", "universal"
            ),
            PostSeed(
                "szok najlepsza oferta ceramic coating tanio",
                "SZOK!!! NAJLEPSZA OFERTA NA CERAMIKĘ!!! MUSISZ TO MIEĆ!!!",
                "DISLIKE", 1L, "casual", "short", "ceramic", "universal"
            ),
            PostSeed(
                "ostatnie sztuki promocja detailing nie przegap",
                "NIE PRZEGAP!!! OSTATNIE MIEJSCA NA DETAILING!!! ZAPISZ SIĘ TERAZ!!!",
                "DISLIKE", 1L, "casual", "short", "detailing", "universal"
            ),

            // ══════════════════════════════════════════════════════
            // userId=2: inny studio, preferuje emotional + casual
            // ══════════════════════════════════════════════════════

            // LIKE / emotional / full / ppf
            PostSeed(
                "historia samochodu właściciel folia PPF ochrona emocje",
                "Właściciel tego Mustanga jeździ nim od 15 lat🖤\nKażda rysa opowiadała historię. Teraz folia PPF daje mu nowy start.\nOddajesz nam samochód. Odbierasz dzieło sztuki.\n📍AutoSpa Studio",
                "LIKE", 2L, "emotional", "full", "ppf", "Ford"
            ),
            PostSeed(
                "marzenie dzieciństwa samochód detailing emocje pasja",
                "Pamiętasz swoje marzenie z dzieciństwa?🚗\nOn je spełnił. A my zadbaliśmy, żeby wyglądało perfekcyjnie.\nDetailing to nie usługa — to szacunek dla marzeń.\n📍AutoSpa Studio",
                "LIKE", 2L, "emotional", "full", "detailing", "universal"
            ),
            PostSeed(
                "weekend samochód wolność ceramic coating emocje",
                "Weekend. Droga. Twój samochód. Wolność.🌅\nPowłoka ceramiczna — żeby ten moment trwał wiecznie.\n📍AutoSpa Studio",
                "LIKE", 2L, "emotional", "short", "ceramic", "universal"
            ),

            // LIKE / casual / short / detailing
            PostSeed(
                "before after efekt wow detailing casual instagram",
                "Slide żeby zobaczyć różnicę 👉\nTak, to ten sam samochód 😱\n📍AutoSpa Studio",
                "LIKE", 2L, "casual", "short", "detailing", "universal"
            ),
            PostSeed(
                "piątek weekend samochód gotowy casual detailing",
                "Piątek = samochód gotowy na weekend 🎉\nCzysty, lśniący, pachnący. Gotowy?\n📍AutoSpa Studio",
                "LIKE", 2L, "casual", "short", "detailing", "universal"
            ),

            // DISLIKE / userId=2 — suchy korporacyjny ton
            PostSeed(
                "informujemy usługa detailing korporacyjny sztywny",
                "Uprzejmie informujemy, że nasza firma oferuje usługi detailingowe na najwyższym poziomie. Zapraszamy do kontaktu.",
                "DISLIKE", 2L, "premium", "short", "detailing", "universal"
            ),
            PostSeed(
                "oferta cennik detailing korporacyjny sztywny",
                "Szanowni Państwo, przedstawiamy aktualny cennik usług detailingowych. Prosimy o zapoznanie się z ofertą.",
                "DISLIKE", 2L, "premium", "short", "detailing", "universal"
            )
        )

        // Wstaw do ad_headlines
        posts.forEach { p ->
            jdbcTemplate.update(
                "INSERT INTO ad_headlines (headline_text, feedback_status, user_id) VALUES (?, ?, ?)",
                p.fullContent, p.feedbackStatus, p.userId
            )
        }
        logger.info("Inserted {} headlines into ad_headlines", posts.size)

        // Indeksuj w VectorStore — embedding z skondensowanego tekstu, pełna treść w metadata
        val documents = posts.map { p ->
            Document.builder()
                .text(p.embeddingText)
                .metadata(
                    mapOf(
                        "feedback_status" to p.feedbackStatus,
                        "user_id" to p.userId.toString(),
                        "post_tone" to p.postTone,
                        "post_length" to p.postLength,
                        "service_type" to p.serviceType,
                        "car_brand" to p.carBrand,
                        "full_content" to p.fullContent
                    )
                )
                .build()
        }

        vectorStore.add(documents)
        logger.info("Seeded {} posts ({} documents indexed)", posts.size, documents.size)

        return SeedResult(headlinesInserted = posts.size, documentsIndexed = documents.size)
    }

    /**
     * GET /api/seed/status – sprawdza ile rekordów jest w bazie i VectorStore.
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any> {
        val headlineCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ad_headlines", Int::class.java) ?: 0
        val vectorCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Int::class.java) ?: 0

        val byStatus = jdbcTemplate.queryForList(
            """SELECT feedback_status, user_id, COUNT(*) as cnt 
               FROM ad_headlines 
               GROUP BY feedback_status, user_id 
               ORDER BY user_id, feedback_status"""
        )

        return mapOf(
            "ad_headlines_count" to headlineCount,
            "vector_store_count" to vectorCount,
            "breakdown" to byStatus
        )
    }
}
