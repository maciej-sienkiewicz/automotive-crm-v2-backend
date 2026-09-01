package pl.akmf.ksef.sdk.client.model.invoice;
import java.time.LocalDate;
import java.time.OffsetDateTime;
public class InvoiceMetadata {
    public String getKsefNumber() { return null; }
    public String getInvoiceNumber() { return null; }
    public OffsetDateTime getInvoicingDate() { return null; }
    public LocalDate getIssueDate() { return null; }
    public InvoiceSubject getSeller() { return null; }
    public InvoiceSubject getBuyer() { return null; }
    public Double getNetAmount() { return null; }
    public Double getVatAmount() { return null; }
    public Double getGrossAmount() { return null; }
    public String getCurrency() { return null; }
    public String getInvoiceHash() { return null; }
    public InvoiceType getInvoiceType() { return null; }
}
