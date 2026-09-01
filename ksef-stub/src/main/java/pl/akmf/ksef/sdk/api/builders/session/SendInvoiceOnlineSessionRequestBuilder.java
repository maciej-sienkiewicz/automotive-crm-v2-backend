package pl.akmf.ksef.sdk.api.builders.session;
import pl.akmf.ksef.sdk.client.model.session.SendInvoiceRequest;
public class SendInvoiceOnlineSessionRequestBuilder {
    public SendInvoiceOnlineSessionRequestBuilder withInvoiceHash(String v) { return this; }
    public SendInvoiceOnlineSessionRequestBuilder withInvoiceSize(long v) { return this; }
    public SendInvoiceOnlineSessionRequestBuilder withEncryptedInvoiceHash(String v) { return this; }
    public SendInvoiceOnlineSessionRequestBuilder withEncryptedInvoiceSize(long v) { return this; }
    public SendInvoiceOnlineSessionRequestBuilder withEncryptedInvoiceContent(String v) { return this; }
    public SendInvoiceRequest build() { return new SendInvoiceRequest(); }
}
