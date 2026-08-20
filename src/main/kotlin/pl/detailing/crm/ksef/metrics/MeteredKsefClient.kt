package pl.detailing.crm.ksef.metrics

import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Opakowuje klienta KSeF w licznik żądań.
 *
 * Metrykowanie w miejscach wywołań wymagałoby dopisania licznika w każdej ścieżce —
 * uwierzytelnianiu, wysyłce, pollingu sesji, pobieraniu UPO, pullu metadanych,
 * pobieraniu XML — i pilnowania, żeby autor kolejnej ścieżki o tym pamiętał. Skoro
 * cała komunikacja przechodzi przez jeden interfejs, licznik jest jego dekoratorem:
 * nowa operacja SDK jest widoczna w metrykach od pierwszego użycia, bez zmian w kodzie.
 *
 * Dynamiczne proxy, a nie ręczna implementacja: [KSeFClient] ma kilkadziesiąt metod,
 * z których używamy kilkunastu, a ręczne delegowanie całości byłoby ścianą kodu
 * psującą się przy każdej aktualizacji SDK.
 */
object MeteredKsefClient {

    /** Metody z Object — nie są żądaniami do KSeF i nie mogą zaśmiecać metryk. */
    private val NON_API_METHODS = setOf("equals", "hashCode", "toString")

    fun wrap(delegate: KSeFClient, metrics: KsefApiMetrics): KSeFClient {
        val proxy = Proxy.newProxyInstance(
            KSeFClient::class.java.classLoader,
            arrayOf(KSeFClient::class.java)
        ) { _, method, args ->
            if (method.name in NON_API_METHODS) {
                invoke(method, delegate, args)
            } else {
                invokeMetered(method, delegate, args, metrics)
            }
        }
        return proxy as KSeFClient
    }

    private fun invokeMetered(
        method: Method,
        delegate: KSeFClient,
        args: Array<out Any?>?,
        metrics: KsefApiMetrics
    ): Any? {
        val studioTag = KsefTenantContext.currentStudioTag()
        val operation = toSnakeCase(method.name)
        return try {
            val result = invoke(method, delegate, args)
            metrics.record(studioTag, operation, KsefApiMetrics.OUTCOME_SUCCESS)
            result
        } catch (e: InvocationTargetException) {
            // Proxy opakowuje każdy wyjątek metody; wołający musi zobaczyć oryginał,
            // inaczej obsługa 429 i błędów w handlerach przestaje rozpoznawać przyczynę
            val cause = e.targetException
            metrics.record(studioTag, operation, outcomeOf(cause))
            throw cause
        }
    }

    private fun invoke(method: Method, delegate: KSeFClient, args: Array<out Any?>?): Any? =
        if (args == null) method.invoke(delegate) else method.invoke(delegate, *args)

    /**
     * KSeF zwraca 429 z opisem w treści błędu, a SDK opakowuje odpowiedź w wyjątek
     * z komunikatem zawierającym kod statusu — stąd rozpoznanie po treści, tak samo
     * jak w ścieżce pobierania XML.
     */
    private fun outcomeOf(cause: Throwable): String =
        if (cause.message?.contains("429") == true) {
            KsefApiMetrics.OUTCOME_RATE_LIMITED
        } else {
            KsefApiMetrics.OUTCOME_ERROR
        }

    /** getAuthChallenge → get_auth_challenge; etykiety Prometheusa trzymamy w snake_case. */
    private fun toSnakeCase(methodName: String): String =
        methodName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}
