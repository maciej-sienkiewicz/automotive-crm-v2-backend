package pl.detailing.crm.signing.infrastructure

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.SignerInformationStore
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import org.bouncycastle.tsp.TimeStampToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Hashtable

/**
 * Applies the company's qualified electronic seal (kwalifikowana pieczęć elektroniczna)
 * with a qualified RFC 3161 timestamp to a finished PDF.
 *
 * Format: PAdES (ETSI.CAdES.detached) baseline T —
 *  - CMS SignedData with the ESS signing-certificate-v2 signed attribute,
 *  - NO signingTime signed attribute (forbidden by PAdES; the /M dictionary entry rules),
 *  - the TSA token embedded as the id-aa-signatureTimeStampToken unsigned attribute.
 *
 * From the moment the seal is applied the PDF is tamper-evident: extracting the visual
 * signature and pasting it into another document produces a file WITHOUT a valid seal
 * and timestamp, so the copied document is immediately distinguishable from the original
 * (art. 35 ust. 2 eIDAS — presumption of integrity and authenticity).
 */
interface QualifiedSealService {
    fun seal(pdfBytes: ByteArray): SealResult

    /** Human-readable description embedded on the audit page. */
    fun describe(): String
}

data class SealResult(
    val pdfBytes: ByteArray,
    val sealApplied: Boolean,
    val timestampApplied: Boolean
)

class SealingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Service
class PadesQualifiedSealService(
    @Value("\${signing.seal.enabled:false}") private val enabled: Boolean,
    @Value("\${signing.seal.keystore-path:}") private val keystorePath: String,
    @Value("\${signing.seal.keystore-password:}") private val keystorePassword: String,
    @Value("\${signing.seal.key-alias:}") private val keyAlias: String,
    @Value("\${signing.seal.key-password:}") private val keyPassword: String,
    @Value("\${signing.seal.tsa-url:}") private val tsaUrl: String,
    @Value("\${signing.seal.seal-name:Pieczęć elektroniczna}") private val sealName: String,
    @Value("\${signing.seal.location:}") private val location: String,
    @Value("\${signing.seal.reason:Zabezpieczenie integralności podpisanego protokołu}") private val reason: String,
    /**
     * true  → sealing failure aborts the signing transaction (fail-closed, production),
     * false → the unsealed PDF is stored with sealApplied=false and a prominent warning (dev).
     */
    @Value("\${signing.seal.required:false}") private val sealRequired: Boolean
) : QualifiedSealService {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
        private const val TSA_TIMEOUT_MS = 15_000
    }

    override fun describe(): String = when {
        !isConfigured() ->
            "UWAGA: kwalifikowana pieczęć elektroniczna nie została nałożona (usługa pieczęci " +
                "nie jest skonfigurowana w tym środowisku)."
        tsaUrl.isBlank() ->
            "Dokument opieczętowany pieczęcią elektroniczną (PAdES, ETSI.CAdES.detached). " +
                "Znacznik czasu: brak (TSA nieskonfigurowane)."
        else ->
            "Dokument opieczętowany kwalifikowaną pieczęcią elektroniczną (PAdES, " +
                "ETSI.CAdES.detached) wraz z kwalifikowanym znacznikiem czasu RFC 3161 (TSA: $tsaUrl)."
    }

    override fun seal(pdfBytes: ByteArray): SealResult {
        if (!isConfigured()) {
            val message = "Qualified seal is NOT configured — the signed PDF is stored WITHOUT " +
                "an electronic seal. Configure signing.seal.* for production use."
            if (sealRequired) throw SealingException(message)
            logger.warn(message)
            return SealResult(pdfBytes, sealApplied = false, timestampApplied = false)
        }

        return try {
            doSeal(pdfBytes)
        } catch (e: Exception) {
            if (sealRequired) {
                throw SealingException("Nałożenie pieczęci elektronicznej nie powiodło się: ${e.message}", e)
            }
            logger.error("Sealing failed — storing UNSEALED document (signing.seal.required=false)", e)
            SealResult(pdfBytes, sealApplied = false, timestampApplied = false)
        }
    }

    private fun isConfigured(): Boolean =
        enabled && keystorePath.isNotBlank() && keyAlias.isNotBlank()

    private fun doSeal(pdfBytes: ByteArray): SealResult {
        val (privateKey, certificateChain) = loadKeyMaterial()
        val signingCertificate = certificateChain.first()
        var timestampApplied = false

        val signatureInterface = SignatureInterface { content: InputStream ->
            var cms = generateCmsSignature(content.readBytes(), privateKey, certificateChain)
            if (tsaUrl.isNotBlank()) {
                cms = addSignatureTimestamp(cms)
                timestampApplied = true
            }
            cms.getEncoded("DER")
        }

        Loader.loadPDF(pdfBytes).use { document ->
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                name = sealName
                if (this@PadesQualifiedSealService.location.isNotBlank()) {
                    location = this@PadesQualifiedSealService.location
                }
                reason = this@PadesQualifiedSealService.reason
                signDate = Calendar.getInstance()
            }

            // Reserve enough space for the CMS container + TSA token
            val options = SignatureOptions().apply {
                preferredSignatureSize = SignatureOptions.DEFAULT_SIGNATURE_SIZE * 3
            }

            document.addSignature(signature, signatureInterface, options)

            val output = ByteArrayOutputStream()
            document.saveIncremental(output)

            logger.info(
                "Applied electronic seal (subject='{}', timestamp={})",
                signingCertificate.subjectX500Principal.name, timestampApplied
            )
            return SealResult(output.toByteArray(), sealApplied = true, timestampApplied = timestampApplied)
        }
    }

    private fun loadKeyMaterial(): Pair<PrivateKey, List<X509Certificate>> {
        val keystore = KeyStore.getInstance("PKCS12")
        FileInputStream(keystorePath).use { stream ->
            keystore.load(stream, keystorePassword.toCharArray())
        }
        val effectiveKeyPassword = keyPassword.ifBlank { keystorePassword }
        val privateKey = keystore.getKey(keyAlias, effectiveKeyPassword.toCharArray()) as? PrivateKey
            ?: throw SealingException("Brak klucza prywatnego pod aliasem '$keyAlias' w magazynie pieczęci")
        val chain = keystore.getCertificateChain(keyAlias)
            ?.mapNotNull { it as? X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw SealingException("Brak łańcucha certyfikatów pod aliasem '$keyAlias'")
        return privateKey to chain
    }

    private fun generateCmsSignature(
        content: ByteArray,
        privateKey: PrivateKey,
        certificateChain: List<X509Certificate>
    ): CMSSignedData {
        val certificate = certificateChain.first()
        val signatureAlgorithm = when (privateKey.algorithm.uppercase()) {
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> "SHA256withRSA"
        }

        val contentSigner = JcaContentSignerBuilder(signatureAlgorithm)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(privateKey)

        // PAdES: include ESS signing-certificate-v2, exclude signingTime
        val certDigest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val signingCertV2 = SigningCertificateV2(
            arrayOf(ESSCertIDv2(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), certDigest))
        )
        val extraAttrs = ASN1EncodableVector().apply {
            add(Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(signingCertV2)))
        }
        val signedAttributeGenerator = object : DefaultSignedAttributeTableGenerator(AttributeTable(extraAttrs)) {
            override fun createStandardAttributeTable(parameters: Map<*, *>?): Hashtable<*, *> {
                val table = super.createStandardAttributeTable(parameters)
                table.remove(CMSAttributes.signingTime)
                return table
            }
        }

        val digestProvider = JcaDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build()

        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(digestProvider)
                    .setSignedAttributeGenerator(signedAttributeGenerator)
                    .build(contentSigner, certificate)
            )
            addCertificates(JcaCertStore(certificateChain))
        }

        // encapsulate=false → detached CMS as required by PAdES
        return generator.generate(CMSProcessableByteArray(content), false)
    }

    /** Embed the RFC 3161 token as the id-aa-signatureTimeStampToken unsigned attribute. */
    private fun addSignatureTimestamp(signedData: CMSSignedData): CMSSignedData {
        val signer = signedData.signerInfos.signers.first()
        val token = requestTimestampToken(signer.signature)

        val vector = ASN1EncodableVector().apply {
            add(
                Attribute(
                    PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                    DERSet(ASN1Primitive.fromByteArray(token.encoded))
                )
            )
        }
        val updatedSigner = SignerInformation.replaceUnsignedAttributes(signer, AttributeTable(vector))
        return CMSSignedData.replaceSigners(signedData, SignerInformationStore(listOf(updatedSigner)))
    }

    private fun requestTimestampToken(signatureBytes: ByteArray): TimeStampToken {
        val digest = MessageDigest.getInstance("SHA-256").digest(signatureBytes)
        val requestGenerator = TimeStampRequestGenerator().apply { setCertReq(true) }
        val request = requestGenerator.generate(TSPAlgorithms.SHA256, digest, BigInteger(64, SecureRandom()))

        val connection = URI(tsaUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TSA_TIMEOUT_MS
            connection.readTimeout = TSA_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/timestamp-query")
            connection.outputStream.use { it.write(request.encoded) }

            if (connection.responseCode != 200) {
                throw SealingException("TSA zwróciło status HTTP ${connection.responseCode}")
            }
            val responseBytes = connection.inputStream.use { it.readBytes() }
            val response = TimeStampResponse(responseBytes)
            response.validate(request)
            return response.timeStampToken
                ?: throw SealingException("TSA nie zwróciło tokenu znacznika czasu (status=${response.status})")
        } finally {
            connection.disconnect()
        }
    }
}
