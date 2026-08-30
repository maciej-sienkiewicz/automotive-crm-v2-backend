package pl.detailing.crm.carddav

import jakarta.servlet.http.HttpServletRequest

/**
 * Publiczny adres, pod którym klient faktycznie widzi API. Za reverse proxy
 * scheme/host/port żądania opisują wnętrze sieci (http, port 8080), nie adres
 * z paska przeglądarki — prawdę niosą nagłówki X-Forwarded-*. Ta sama logika,
 * którą CardDavController buduje adresy w odpowiedziach PROPFIND.
 *
 * Dzięki wyprowadzaniu adresu z żądania link instalacyjny i host w profilu
 * zawsze wskazują domenę, przez którą użytkownik naprawdę wszedł — bez
 * konfigurowania czegokolwiek per środowisko.
 */
object PublicBaseUrl {

    fun of(request: HttpServletRequest): String {
        val scheme = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host") ?: request.serverName
        val port = request.getHeader("X-Forwarded-Port")?.toIntOrNull()
            ?: if (request.getHeader("X-Forwarded-Proto") != null) (if (scheme == "https") 443 else 80)
            else request.serverPort
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** Host dla payloadu CardDAV w profilu (bez schematu i portu). */
    fun hostName(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-Host") ?: request.serverName
}
