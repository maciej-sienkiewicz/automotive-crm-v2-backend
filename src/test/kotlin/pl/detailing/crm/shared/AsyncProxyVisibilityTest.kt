package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.Aware
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.ClassUtils
import java.io.Closeable
import java.lang.reflect.Method

/**
 * Bean z `@Async`, który implementuje interfejs, dostaje proxy JDK — i to proxy
 * wystawia WYŁĄCZNIE metody interfejsów.
 *
 * Aplikacja ma `@EnableAsync` z domyślnym `proxyTargetClass = false`, więc
 * AsyncAnnotationBeanPostProcessor decyduje o rodzaju proxy tak samo jak
 * ProxyProcessorSupport.evaluateProxyInterfaces: jest „rozsądny" interfejs → proxy JDK.
 * Wszystko, co żyje poza interfejsem, jest dla Springa niewidoczne, a wychodzi to
 * dopiero przy budowie kontekstu, czyli na wdrożeniu:
 *
 *  - nasłuch (`@EventListener`, `@TransactionalEventListener`) albo `@Scheduled`
 *    zadeklarowany obok metod interfejsu — zgłoszenie z produkcji, FormThreadAutoUntangler:
 *      Need to invoke method 'onSourceRegistered' declared on target class
 *      'FormThreadAutoUntangler', but not found in any interface(s) of the exposed proxy type
 *  - wstrzyknięcie takiego beana po KLASIE, a nie po interfejsie:
 *      Bean named '…' is expected to be of type '…' but was actually of type 'jdk.proxy…'
 *
 * Bean z `@Transactional` jest bezpieczny: auto-proxy Spring Boota (CGLIB, najwyższy
 * priorytet) owija go wcześniej, a `@Async` dokłada się do tamtego proxy.
 *
 * Test czyta adnotacje i sygnatury refleksją, bez kontekstu — nie potrzebuje bazy ani
 * Redisa, a martwe wdrożenie zamienia w czerwony build u autora zmiany.
 */
class AsyncProxyVisibilityTest {

    /** Interfejsy, których Spring nie traktuje jako powodu do proxy JDK. */
    private val callbackInterfaces = listOf(
        InitializingBean::class.java, DisposableBean::class.java, Closeable::class.java,
        AutoCloseable::class.java, Aware::class.java
    )

    private val components: List<Class<*>> by lazy {
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(Component::class.java)) }
            .findCandidateComponents("pl.detailing.crm")
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it, false, javaClass.classLoader) }
    }

    private fun hasAsync(type: Class<*>): Boolean =
        AnnotatedElementUtils.hasAnnotation(type, Async::class.java) ||
            type.declaredMethods.any { AnnotatedElementUtils.hasAnnotation(it, Async::class.java) }

    private fun hasTransactional(type: Class<*>): Boolean =
        AnnotatedElementUtils.hasAnnotation(type, Transactional::class.java) ||
            type.declaredMethods.any { AnnotatedElementUtils.hasAnnotation(it, Transactional::class.java) }

    private fun proxyInterfaces(type: Class<*>): List<Class<*>> =
        ClassUtils.getAllInterfacesForClassAsSet(type).filter { ifc ->
            callbackInterfaces.none { it.isAssignableFrom(ifc) } && ifc.methods.isNotEmpty()
        }

    /** Beany, które `@EnableAsync` owinie w proxy JDK. */
    private val jdkProxied: List<Class<*>> by lazy {
        components.filter { hasAsync(it) && !hasTransactional(it) && proxyInterfaces(it).isNotEmpty() }
    }

    private fun Method.declaredIn(interfaces: List<Class<*>>): Boolean =
        interfaces.any { ifc -> runCatching { ifc.getMethod(name, *parameterTypes) }.isSuccess }

    @Test
    fun `nasluchy i zadania harmonogramu beana z proxy JDK sa widoczne przez interfejs`() {
        val offenders = jdkProxied.flatMap { type ->
            val interfaces = proxyInterfaces(type)
            type.declaredMethods
                .filter {
                    AnnotatedElementUtils.hasAnnotation(it, EventListener::class.java) ||
                        AnnotatedElementUtils.hasAnnotation(it, Scheduled::class.java)
                }
                .filterNot { it.declaredIn(interfaces) }
                .map { "${type.simpleName}.${it.name}() — proxy wystawia tylko ${interfaces.map { i -> i.simpleName }}" }
        }.sorted()

        assertTrue(
            offenders.isEmpty(),
            """
            Nasłuchy/zadania niewidoczne przez proxy JDK beana z @Async:
            ${offenders.joinToString("\n            ")}

            Spring odmówi budowy kontekstu i aplikacja nie wstanie. Przenieś nasłuch
            do osobnego beana bez interfejsu (tak jak FormMailSourceSyncTrigger).
            """.trimIndent()
        )
    }

    @Test
    fun `bean z proxy JDK nie jest wstrzykiwany po klasie`() {
        val proxied = jdkProxied.toSet()
        val offenders = components.flatMap { consumer ->
            consumer.declaredConstructors
                .flatMap { it.parameterTypes.toList() }
                .filter { it in proxied }
                .map { "${consumer.simpleName} ← ${it.simpleName}" }
        }.distinct().sorted()

        assertTrue(
            offenders.isEmpty(),
            """
            Beany z @Async wstrzykiwane po klasie, choć dostają proxy JDK:
            ${offenders.joinToString("\n            ")}

            Wstrzykuj po interfejsie albo wydziel metodę @Async do osobnego beana.
            """.trimIndent()
        )
    }

    @Test
    fun `skan w ogole widzi beany z Async`() {
        // Bez tego testy wyżej przechodziłyby również wtedy, gdyby skan niczego nie
        // znalazł — np. po zmianie pakietu bazowego albo filtra komponentów.
        val async = components.count(::hasAsync)
        assertTrue(async >= 10, "Skan znalazł tylko $async beanów z @Async — filtr przestał działać?")
    }
}
