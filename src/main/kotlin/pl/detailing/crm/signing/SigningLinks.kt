package pl.detailing.crm.signing

import java.security.SecureRandom
import java.util.Base64

/**
 * Token linku do podpisu wysyłanego SMS-em: 32 losowe bajty (256 bitów), base64url bez
 * dopełnienia. Token JEST poświadczeniem - kto go ma, otwiera dokument i może go podpisać
 * (patrz [PublicSignatureController]) - więc tylko z [SecureRandom].
 */
fun newSigningLinkToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Adres strony podpisu na telefonie (`/sign/{token}` we froncie). */
fun signingLinkUrl(frontendBaseUrl: String, token: String): String =
    "${frontendBaseUrl.trimEnd('/')}/sign/$token"
