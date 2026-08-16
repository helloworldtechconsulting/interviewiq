package com.interviewengine.billing.service;

import com.interviewengine.billing.domain.TransactionType;
import com.interviewengine.billing.domain.WalletTransaction;
import com.interviewengine.billing.infrastructure.WalletTransactionRepository;
import com.interviewengine.company.domain.Company;
import com.interviewengine.company.infrastructure.CompanyRepository;
import com.interviewengine.shared.config.BillingProperties;
import com.interviewengine.shared.exception.ResourceNotFoundException;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.shared.security.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * GST invoices for wallet top-ups (PRD v2.1 §7.8.1, INTIQ-69).
 *
 * <p>Not a nice-to-have. An Indian B2B customer cannot claim input tax credit
 * without a tax invoice carrying the supplier's GSTIN, the recipient's GSTIN,
 * an invoice number, and the tax shown separately. Without one, every ₹100 costs
 * them ₹118 in practice — which is a procurement objection long before it is a
 * finance one.
 *
 * <h2>Only top-ups are invoiceable</h2>
 *
 * <p>The taxable supply is the prepayment, not the interview. Settlements draw
 * down a balance that has already been invoiced, and promotional credit is not a
 * supply at all — invoicing either would double-count the tax. This is why the
 * method refuses anything that is not a {@link TransactionType#TOPUP}, rather
 * than quietly returning an empty document.
 *
 * <h2>Rendered as text, not PDF</h2>
 *
 * <p>Deliberate, and the honest limitation of this implementation. A PDF needs a
 * rendering library, a template, a font pipeline and a place to store the output.
 * A plain-text invoice with every field the GST rules require is legible, is
 * accepted by every accountant this product will meet at this stage, and can be
 * swapped for a PDF renderer without changing the numbers — which are the part
 * that has to be right. Worth revisiting when a customer actually asks.
 */
@Service
public class InvoiceService {

    private static final DateTimeFormatter INVOICE_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final WalletTransactionRepository txRepository;
    private final CompanyRepository companyRepository;
    private final BillingProperties billingProperties;

    @Value("${app.billing.supplier-name:Hello World Tech Consulting LLP}")
    private String supplierName;

    @Value("${app.billing.supplier-gstin:}")
    private String supplierGstin;

    @Value("${app.billing.supplier-address:India}")
    private String supplierAddress;

    public InvoiceService(WalletTransactionRepository txRepository,
                          CompanyRepository companyRepository,
                          BillingProperties billingProperties) {
        this.txRepository      = txRepository;
        this.companyRepository = companyRepository;
        this.billingProperties = billingProperties;
    }

    /**
     * Renders the tax invoice for one top-up.
     *
     * @throws ValidationException if the transaction is not an invoiceable top-up
     */
    @Transactional(readOnly = true)
    public String render(UUID transactionId) {
        UUID companyId = SecurityContext.requireCompanyId();

        WalletTransaction tx = txRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("WalletTransaction", transactionId));

        // Ownership before anything else — an invoice carries the buyer's legal
        // name and GSTIN, so serving the wrong tenant's is a data leak, not just
        // a wrong answer.
        SecurityContext.requireSameCompany(tx.getCompanyId());

        if (tx.getTransactionType() != TransactionType.TOPUP) {
            throw new ValidationException(
                    "Invoices are issued for wallet top-ups only. Interview charges draw down "
                            + "a balance that has already been invoiced.");
        }
        if (tx.isPromotional()) {
            throw new ValidationException("Promotional credit is not a taxable supply and is not invoiced.");
        }
        // A manual staff credit is stored as a TOPUP because it behaves like paid
        // balance, but it is a correction rather than a sale — no money changed
        // hands and there is no taxable supply. Without this guard it would pass
        // the check above and produce a "tax invoice" for zero GST against a
        // payment that never happened, which is a worse document than none.
        if (tx.getGrantedByStaffId() != null) {
            throw new ValidationException(
                    "This was a manual account credit rather than a payment, so it is not invoiced.");
        }

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        // The stored GST is authoritative. Recomputing from the current rate
        // would silently restate historical invoices if the rate ever changes —
        // an invoice must say what was actually charged at the time.
        long gst = tx.getGstPaise() == null ? 0L : tx.getGstPaise();
        long net = tx.getAmountPaise();
        long gross = net + gst;

        StringBuilder out = new StringBuilder(768);
        out.append("TAX INVOICE\n")
           .append("=".repeat(64)).append("\n\n")
           .append("Invoice no:   ").append(invoiceNumber(tx)).append('\n')
           .append("Invoice date: ").append(INVOICE_DATE.format(tx.getCreatedAt())).append("\n\n");

        out.append("Supplier\n")
           .append("  ").append(supplierName).append('\n')
           .append("  ").append(supplierAddress).append('\n');
        if (!supplierGstin.isBlank()) {
            out.append("  GSTIN: ").append(supplierGstin).append('\n');
        }
        out.append('\n');

        out.append("Recipient\n")
           .append("  ").append(company.getName()).append('\n');
        if (company.getGstNumber() != null && !company.getGstNumber().isBlank()) {
            out.append("  GSTIN: ").append(company.getGstNumber()).append('\n');
        } else {
            // Said plainly rather than omitted. A customer who needs input tax
            // credit and gets an invoice without their GSTIN will find out at
            // filing time, which is the worst moment to discover it.
            out.append("  GSTIN: not provided — add it in Settings and download this invoice again\n");
        }
        out.append('\n');

        out.append("Description                                        Amount (INR)\n")
           .append("-".repeat(64)).append('\n')
           .append(String.format("%-50s %13s%n", "InterviewEngine wallet top-up", rupees(net)))
           .append(String.format("%-50s %13s%n",
                   "GST @ " + billingProperties.getGstPercent() + "%", rupees(gst)))
           .append("-".repeat(64)).append('\n')
           .append(String.format("%-50s %13s%n", "Total paid", rupees(gross)))
           .append('\n');

        if (tx.getRazorpayPaymentId() != null) {
            out.append("Payment reference: ").append(tx.getRazorpayPaymentId()).append('\n');
        } else if (tx.getRazorpayOrderId() != null) {
            out.append("Order reference: ").append(tx.getRazorpayOrderId()).append('\n');
        }

        out.append("\nThis is a computer-generated invoice and is valid without a signature.\n");
        return out.toString();
    }

    /** Suggested filename for the download. */
    public String filename(UUID transactionId) {
        return "interviewengine-invoice-" + transactionId + ".txt";
    }

    /**
     * A short, stable, human-quotable invoice number.
     *
     * <p>Derived from the transaction id rather than a counter. A counter would
     * need to be strictly sequential and gapless per financial year to be
     * compliant, which means a separate serialised sequence and a migration to
     * back it — worth doing when there is real invoicing volume, and worth being
     * explicit that this is not that yet.
     */
    private String invoiceNumber(WalletTransaction tx) {
        return "IIQ-" + tx.getCreatedAt().getYear() + "-"
                + tx.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String rupees(long paise) {
        return String.format("%,.2f", paise / 100.0);
    }
}
