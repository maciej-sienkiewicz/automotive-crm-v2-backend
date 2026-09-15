package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Nasłuch transakcyjny z `@Transactional` musi mieć własną propagację.
 *
 * Spring od 6.1 sprawdza to sam, przez RestrictedTransactionalEventListenerFactory —
 * tyle że dopiero przy budowie kontekstu, czyli na wdrożeniu:
 *
 *   @TransactionalEventListener method must not be annotated with @Transactional
 *   unless when declared as REQUIRES_NEW or NOT_SUPPORTED
 *
 * Zakaz ma sens: przy AFTER_COMMIT transakcja wołającego jest już zamknięta, więc
 * gołe `@Transactional` (PROPAGATION_REQUIRED) albo nie ma do czego dołączyć, albo —
 * gorzej — dopisuje zapisy nasłuchu do cudzej jednostki pracy i pozwala mu ją
 * oznaczyć jako rollback-only. Dokładnie przed tym broni się KDoc
 * [pl.detailing.crm.leads.update.LeadFirstResponseListener]: import wiadomości to
 * jedna transakcja i zatruty nasłuch kosztował całą wiadomość, bez śladu w CRM-ie.
 *
 * Test czyta adnotacje z bajtkodu (ASM, bez ładowania klas i bez kontekstu), więc
 * kosztuje ułamek sekundy i nie potrzebuje ani bazy, ani Redisa. Zamienia martwe
 * wdrożenie w czerwony build u autora zmiany.
 */
class TransactionalEventListenerPropagationTest {

    /** Propagacje, przy których nasłuch ma własną jednostkę pracy — jedyne dozwolone. */
    private val allowed = setOf("REQUIRES_NEW", "NOT_SUPPORTED")

    private val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
        addIncludeFilter(AnnotationTypeFilter(Component::class.java))
    }

    @Test
    fun `nasluch transakcyjny nie nosi golego @Transactional`() {
        val offenders = scanner.findCandidateComponents("pl.detailing.crm")
            .filterIsInstance<AnnotatedBeanDefinition>()
            .flatMap { bean ->
                bean.metadata.getAnnotatedMethods(TransactionalEventListener::class.java.name)
            }
            .filter { method -> method.isAnnotated(Transactional::class.java.name) }
            .filterNot { method ->
                val propagation = method.getAnnotationAttributes(Transactional::class.java.name)
                    ?.get("propagation")?.toString()
                propagation in allowed
            }
            .map { "${it.declaringClassName}.${it.methodName}()" }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            """
            Nasłuchy transakcyjne z @Transactional bez własnej propagacji:
            ${offenders.joinToString("\n            ")}

            Spring odmówi budowy kontekstu i aplikacja nie wstanie:
              @TransactionalEventListener method must not be annotated with
              @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED

            Właściwy zapis (tak robią pozostałe nasłuchy leadów):
              @Transactional(propagation = Propagation.REQUIRES_NEW)
            """.trimIndent()
        )
    }

    @Test
    fun `skan w ogole widzi nasluchy transakcyjne`() {
        // Bez tego pierwszy test przechodziłby również wtedy, gdyby skan niczego nie
        // znalazł — np. po zmianie pakietu bazowego albo filtra komponentów.
        val listeners = scanner.findCandidateComponents("pl.detailing.crm")
            .filterIsInstance<AnnotatedBeanDefinition>()
            .flatMap { it.metadata.getAnnotatedMethods(TransactionalEventListener::class.java.name) }

        assertTrue(
            listeners.size >= 10,
            "Skan znalazł tylko ${listeners.size} nasłuchów transakcyjnych — filtr przestał działać?"
        )
    }
}
