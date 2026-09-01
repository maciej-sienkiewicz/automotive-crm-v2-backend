package pl.akmf.ksef.sdk.api.services;
import java.time.OffsetDateTime;
import pl.akmf.ksef.sdk.client.interfaces.CryptographyService;
import pl.akmf.ksef.sdk.client.interfaces.EncryptionData;
import pl.akmf.ksef.sdk.client.interfaces.FileMetadata;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.system.KsefIntegrationMode;
public class DefaultCryptographyService implements CryptographyService {
    public DefaultCryptographyService(KSeFClient client) {}
    private static RuntimeException stub() { return new UnsupportedOperationException("KSeF SDK stub - compile only"); }
    public KsefIntegrationMode getKsefIntegrationMode() { throw stub(); }
    public void initCryptographyService() { throw stub(); }
    public byte[] encryptKsefTokenUsingPublicKey(String token, OffsetDateTime challengeTimestamp) { throw stub(); }
    public EncryptionData getEncryptionData() { throw stub(); }
    public byte[] encryptBytesWithAES256(byte[] content, byte[] key, byte[] iv) { throw stub(); }
    public FileMetadata getMetaData(byte[] content) { throw stub(); }
}
