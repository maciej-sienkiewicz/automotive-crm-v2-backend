package pl.akmf.ksef.sdk.client.interfaces;
import java.util.List;
import pl.akmf.ksef.sdk.client.model.UpoVersion;
import pl.akmf.ksef.sdk.client.model.auth.*;
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryFilters;
import pl.akmf.ksef.sdk.client.model.invoice.QueryInvoiceMetadataResponse;
import pl.akmf.ksef.sdk.client.model.session.OpenOnlineSessionRequest;
import pl.akmf.ksef.sdk.client.model.session.SendInvoiceRequest;
import pl.akmf.ksef.sdk.client.model.session.SessionInvoiceStatusResponse;
import pl.akmf.ksef.sdk.client.model.session.SessionReferenceResponse;
import pl.akmf.ksef.sdk.client.model.util.SortOrder;
public interface KSeFClient {
    AuthChallengeResponse getAuthChallenge();
    AuthSubmitResponse authenticateByKSeFToken(AuthKsefTokenRequest request);
    TokenResponse redeemToken(String tempToken);
    AuthStatusResponse getAuthStatus(String referenceNumber, String tempToken);
    QueryTokensResponse queryKsefTokens(List<AuthenticationTokenStatus> statuses, String a, String b, String c, String d, int pageSize, String accessToken);
    byte[] getInvoice(String ksefNumber, String accessToken);
    QueryInvoiceMetadataResponse queryInvoiceMetadata(int offset, int pageSize, SortOrder sortOrder, InvoiceQueryFilters filters, String accessToken);
    SessionReferenceResponse openOnlineSession(OpenOnlineSessionRequest request, UpoVersion upoVersion, String accessToken);
    SessionReferenceResponse onlineSessionSendInvoice(String sessionReference, SendInvoiceRequest request, String accessToken);
    void closeOnlineSession(String sessionReference, String accessToken);
    byte[] getSessionInvoiceUpoByKsefNumber(String sessionReference, String ksefNumber, String accessToken);
    SessionInvoiceStatusResponse getSessionInvoiceStatus(String sessionReference, String invoiceReference, String accessToken);
}
