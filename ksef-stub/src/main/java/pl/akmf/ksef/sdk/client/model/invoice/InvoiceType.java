package pl.akmf.ksef.sdk.client.model.invoice;
public enum InvoiceType {
    FA("FA"), FA_KOR("FA_KOR"), KOR("KOR");
    private final String value;
    InvoiceType(String value) { this.value = value; }
    public String getValue() { return value; }
}
