package pl.detailing.crm.carddav

import java.util.UUID

/**
 * Profil konfiguracyjny Apple (.mobileconfig) z payloadem com.apple.carddav.account.
 *
 * To jedyna dostępna z zewnątrz droga założenia konta systemowego na iOS —
 * strona WWW nie ma do tego API. Profil niesie komplet danych (host, login,
 * hasło aplikacyjne), więc po stronie użytkownika zostają dwa dotknięcia
 * „Zainstaluj", których Apple nie pozwala pominąć.
 *
 * Profil jest niepodpisany — iOS pokaże „Niezweryfikowany", ale zainstaluje.
 * Podpis S/MIME (certyfikatem TLS domeny) można dołożyć, owijając wynik tej
 * funkcji w CMS SignedData; struktura XML zostaje ta sama.
 */
object MobileConfigBuilder {

    fun cardDavProfile(
        accountId: UUID,
        hostName: String,
        principalPath: String,
        username: String,
        password: String,
    ): String {
        // PayloadUUID musi być stabilne per konto: ponowna instalacja tego samego
        // profilu nadpisuje konto zamiast dokładać duplikat obok.
        val accountUuid = accountId.toString().uppercase()
        val envelopeUuid = UUID.nameUUIDFromBytes("envelope-$accountId".toByteArray()).toString().uppercase()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>PayloadContent</key>
                <array>
                    <dict>
                        <key>PayloadType</key>
                        <string>com.apple.carddav.account</string>
                        <key>PayloadVersion</key>
                        <integer>1</integer>
                        <key>PayloadIdentifier</key>
                        <string>pl.detailboost.carddav.account.$accountUuid</string>
                        <key>PayloadUUID</key>
                        <string>$accountUuid</string>
                        <key>PayloadDisplayName</key>
                        <string>Kontakty DetailBoost</string>
                        <key>CardDAVAccountDescription</key>
                        <string>DetailBoost — klienci studia</string>
                        <key>CardDAVHostName</key>
                        <string>${escape(hostName)}</string>
                        <key>CardDAVPort</key>
                        <integer>443</integer>
                        <key>CardDAVUseSSL</key>
                        <true/>
                        <key>CardDAVPrincipalURL</key>
                        <string>${escape(principalPath)}</string>
                        <key>CardDAVUsername</key>
                        <string>${escape(username)}</string>
                        <key>CardDAVPassword</key>
                        <string>${escape(password)}</string>
                    </dict>
                </array>
                <key>PayloadType</key>
                <string>Configuration</string>
                <key>PayloadVersion</key>
                <integer>1</integer>
                <key>PayloadIdentifier</key>
                <string>pl.detailboost.carddav.$envelopeUuid</string>
                <key>PayloadUUID</key>
                <string>$envelopeUuid</string>
                <key>PayloadDisplayName</key>
                <string>DetailBoost — kontakty klientów</string>
                <key>PayloadDescription</key>
                <string>Dodaje klientów studia do Kontaktów. Przy połączeniu od razu widać, kto dzwoni.</string>
                <key>PayloadOrganization</key>
                <string>DetailBoost</string>
                <key>PayloadRemovalDisallowed</key>
                <false/>
            </dict>
            </plist>
        """.trimIndent()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
