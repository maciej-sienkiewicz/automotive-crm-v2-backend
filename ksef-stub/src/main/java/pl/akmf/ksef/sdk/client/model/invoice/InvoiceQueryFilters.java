package pl.akmf.ksef.sdk.client.model.invoice;
public class InvoiceQueryFilters {
    private InvoiceQuerySubjectType subjectType;
    private InvoiceQueryDateRange dateRange;
    public InvoiceQuerySubjectType getSubjectType() { return subjectType; }
    public void setSubjectType(InvoiceQuerySubjectType v) { this.subjectType = v; }
    public InvoiceQueryDateRange getDateRange() { return dateRange; }
    public void setDateRange(InvoiceQueryDateRange v) { this.dateRange = v; }
}
