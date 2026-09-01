package pl.akmf.ksef.sdk.client.interfaces;
import java.time.OffsetDateTime;
import pl.akmf.ksef.sdk.system.KsefIntegrationMode;
public interface CryptographyService {
    KsefIntegrationMode getKsefIntegrationMode();
    void initCryptographyService();
    byte[] encryptKsefTokenUsingPublicKey(String token, OffsetDateTime challengeTimestamp);
    EncryptionData getEncryptionData();
    byte[] encryptBytesWithAES256(byte[] content, byte[] key, byte[] iv);
    FileMetadata getMetaData(byte[] content);
}
