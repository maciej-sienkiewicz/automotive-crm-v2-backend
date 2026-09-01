package pl.akmf.ksef.sdk.api;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient;
import pl.akmf.ksef.sdk.client.model.UpoVersion;
import pl.akmf.ksef.sdk.client.model.auth.*;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryFilters;
import pl.akmf.ksef.sdk.client.model.invoice.QueryInvoiceMetadataResponse;
import pl.akmf.ksef.sdk.client.model.session.OpenOnlineSessionRequest;
import pl.akmf.ksef.sdk.client.model.session.SendInvoiceRequest;
import pl.akmf.ksef.sdk.client.model.session.SessionInvoiceStatusResponse;
import pl.akmf.ksef.sdk.client.model.session.SessionReferenceResponse;
import pl.akmf.ksef.sdk.client.model.util.SortOrder;
public class DefaultKsefClient implements KSeFClient {
    public DefaultKsefClient(HttpClient httpClient, KsefApiProperties properties, ObjectMapper objectMapper) {}
    private static RuntimeException stub() { return new UnsupportedOperationException("KSeF SDK stub - compile only"); }
    public AuthChallengeResponse getAuthChallenge() { throw stub(); }
    public AuthSubmitResponse authenticateByKSeFToken(AuthKsefTokenRequest request) { throw stub(); }
    public TokenResponse redeemToken(String tempToken) { throw stub(); }
    public AuthStatusResponse getAuthStatus(String referenceNumber, String tempToken) { throw stub(); }
    public QueryTokensResponse queryKsefTokens(List<AuthenticationTokenStatus> statuses, String a, String b, String c, String d, int pageSize, String accessToken) { throw stub(); }
    public byte[] getInvoice(String ksefNumber, String accessToken) { throw stub(); }
    public QueryInvoiceMetadataResponse queryInvoiceMetadata(int offset, int pageSize, SortOrder sortOrder, InvoiceQueryFilters filters, String accessToken) { throw stub(); }
    public SessionReferenceResponse openOnlineSession(OpenOnlineSessionRequest request, UpoVersion upoVersion, String accessToken) { throw stub(); }
    public SessionReferenceResponse onlineSessionSendInvoice(String sessionReference, SendInvoiceRequest request, String accessToken) { throw stub(); }
    public void closeOnlineSession(String sessionReference, String accessToken) { throw stub(); }
    public byte[] getSessionInvoiceUpoByKsefNumber(String sessionReference, String ksefNumber, String accessToken) { throw stub(); }
    public SessionInvoiceStatusResponse getSessionInvoiceStatus(String sessionReference, String invoiceReference, String accessToken) { throw stub(); }
}
