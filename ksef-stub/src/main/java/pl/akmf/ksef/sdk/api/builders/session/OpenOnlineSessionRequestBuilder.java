package pl.akmf.ksef.sdk.api.builders.session;
import pl.akmf.ksef.sdk.client.model.session.EncryptionInfo;
import pl.akmf.ksef.sdk.client.model.session.FormCode;
import pl.akmf.ksef.sdk.client.model.session.OpenOnlineSessionRequest;
public class OpenOnlineSessionRequestBuilder {
    public OpenOnlineSessionRequestBuilder withFormCode(FormCode formCode) { return this; }
    public OpenOnlineSessionRequestBuilder withEncryptionInfo(EncryptionInfo info) { return this; }
    public OpenOnlineSessionRequest build() { return new OpenOnlineSessionRequest(); }
}
