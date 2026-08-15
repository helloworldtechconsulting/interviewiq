package com.interviewiq.candidate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.billing.service.WalletService;
import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.domain.CandidateImportBatch;
import com.interviewiq.candidate.domain.ImportBatchStatus;
import com.interviewiq.candidate.infrastructure.CandidateImportBatchRepository;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.candidate.service.CandidateCsvParser.ParsedRow;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.infrastructure.JobOpeningRepository;
import com.interviewiq.shared.config.BillingProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.shared.exception.InsufficientBalanceException;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.SessionStateException;
import com.interviewiq.shared.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk candidate import by CSV (PRD v2.1 §7.3.1).
 *
 * <p>Moved into scope from Phase 2 for a plain reason: "a recruiter running a
 * hiring drive will not add fifty candidates one at a time."
 *
 * <p>The flow is upload → column mapping → validation preview → confirm →
 * import, and the ordering is the design. Two rules govern it:
 *
 * <h2>Validate before charging anything</h2>
 *
 * <p>The preview states exactly what will happen — "47 valid, 3 duplicates,
 * 2 invalid emails" — and lets the recruiter fix or skip rows before anything is
 * committed. Duplicates are detected against existing candidates on the same
 * opening.
 *
 * <h2>Reserve for the whole batch atomically, or not at all</h2>
 *
 * <p>"A 50-candidate import that runs out of money at candidate 38 is a support
 * ticket and a half-imported opening. Check the balance up front and refuse the
 * entire import with a clear top-up prompt rather than failing partway."
 *
 * <p>That is why {@link #confirmImport} takes the reservation before writing a
 * single candidate row, inside one transaction: an insufficient balance rolls
 * the whole thing back, leaving the opening exactly as it was.
 */
@Service
public class CandidateImportService {

    private static final Logger log = LoggerFactory.getLogger(CandidateImportService.class);

    private final CandidateImportBatchRepository batchRepository;
    private final CandidateRepository candidateRepository;
    private final JobOpeningRepository jobRepository;
    private final WalletService walletService;
    private final BillingProperties billingProperties;
    private final ObjectMapper objectMapper;

    public CandidateImportService(CandidateImportBatchRepository batchRepository,
                                  CandidateRepository candidateRepository,
                                  JobOpeningRepository jobRepository,
                                  WalletService walletService,
                                  BillingProperties billingProperties,
                                  ObjectMapper objectMapper) {
        this.batchRepository     = batchRepository;
        this.candidateRepository = candidateRepository;
        this.jobRepository       = jobRepository;
        this.walletService       = walletService;
        this.billingProperties   = billingProperties;
        this.objectMapper        = objectMapper;
    }

    // =========================================================================
    // Step 1 — validation preview
    // =========================================================================

    /**
     * Records a batch in PREVIEW and reports exactly what an import would do.
     *
     * <p>Nothing is charged and no candidate is written. The counts returned are
     * the ones §7.3.1 specifies, in the form the recruiter sees.
     */
    @Transactional
    public ImportPreview preview(UUID jobOpeningId,
                                 String fileName,
                                 Map<String, Integer> columnMapping,
                                 List<ParsedRow> rows) {

        JobOpening job = requireJob(jobOpeningId);
        UUID companyId = job.getCompanyId();

        // Duplicates are detected against candidates already on this opening,
        // and against repeats within the file itself — a recruiter's spreadsheet
        // routinely contains the same person twice.
        Set<String> existingEmails = new HashSet<>(
                candidateRepository.findAllEmailsByJobOpeningId(jobOpeningId));
        Set<String> seenInFile = new HashSet<>();

        List<RowOutcome> outcomes = new ArrayList<>(rows.size());
        int valid = 0;
        int duplicates = 0;
        int invalid = 0;

        for (ParsedRow row : rows) {
            if (!row.isValid()) {
                outcomes.add(RowOutcome.invalid(row, row.problems()));
                invalid++;
            } else if (existingEmails.contains(row.email()) || !seenInFile.add(row.email())) {
                outcomes.add(RowOutcome.duplicate(row));
                duplicates++;
            } else {
                outcomes.add(RowOutcome.valid(row));
                valid++;
            }
        }

        CandidateImportBatch batch = new CandidateImportBatch();
        batch.setCompanyId(companyId);
        batch.setJobOpeningId(jobOpeningId);
        batch.setUploadedBy(SecurityContext.requireUserId());
        batch.setFileName(fileName);
        batch.setColumnMappingJson(writeJson(columnMapping));
        batch.setRowCount(rows.size());
        batch.setValidCount(valid);
        batch.setDuplicateCount(duplicates);
        batch.setInvalidCount(invalid);
        batch.setValidationErrorsJson(writeJson(
                outcomes.stream().filter(o -> !o.problems().isEmpty()).toList()));
        batch.setStatus(ImportBatchStatus.PREVIEW);
        batchRepository.save(batch);

        long reservation = batch.requiredReservationPaise(billingProperties.getSessionCostPaise());

        log.info("Import previewed: batchId={} jobId={} {}",
                batch.getId(), jobOpeningId, batch.previewSummary());

        return new ImportPreview(
                batch.getId(),
                rows.size(), valid, duplicates, invalid,
                reservation,
                batch.previewSummary(),
                outcomes);
    }

    // =========================================================================
    // Step 2 — confirm
    // =========================================================================

    /**
     * Imports the valid rows, reserving for the whole batch first.
     *
     * <p>One transaction. The reservation is taken before any candidate row is
     * written, so an insufficient balance rolls everything back and the opening
     * is untouched — never half-imported.
     *
     * @throws InsufficientBalanceException with the shortfall, so the UI can show
     *         a top-up prompt for the right amount
     */
    @Auditable(action = "CANDIDATES_IMPORTED", entityType = "JOB_OPENING", entityIdArg = 0)
    @Transactional
    public ImportResult confirmImport(UUID batchId, List<ParsedRow> rows) {
        CandidateImportBatch batch = requireBatch(batchId);

        if (batch.getStatus() != ImportBatchStatus.PREVIEW) {
            throw new SessionStateException(
                    "This import has already been processed (status: " + batch.getStatus() + ").");
        }

        Set<String> existingEmails = new HashSet<>(
                candidateRepository.findAllEmailsByJobOpeningId(batch.getJobOpeningId()));
        Set<String> seenInFile = new HashSet<>();

        List<ParsedRow> importable = rows.stream()
                .filter(ParsedRow::isValid)
                .filter(r -> !existingEmails.contains(r.email()) && seenInFile.add(r.email()))
                .toList();

        if (importable.isEmpty()) {
            batch.setStatus(ImportBatchStatus.REJECTED);
            batchRepository.save(batch);
            throw new com.interviewiq.shared.exception.ValidationException(
                    "There are no new candidates to import in this file.");
        }

        batch.setStatus(ImportBatchStatus.IMPORTING);

        // THE ATOMIC RESERVATION. Before any row is written, and inside this
        // transaction — so a shortfall rolls back the whole import rather than
        // leaving a half-populated opening behind.
        long required = (long) importable.size() * billingProperties.getSessionCostPaise();
        walletService.reserveFunds(batch.getCompanyId(), batch.getId(), required);
        batch.setReservedAmountPaise(required);

        List<Candidate> created = new ArrayList<>(importable.size());
        for (ParsedRow row : importable) {
            Candidate candidate = new Candidate();
            candidate.setCompanyId(batch.getCompanyId());
            candidate.setJobOpeningId(batch.getJobOpeningId());
            candidate.setEmail(row.email());
            candidate.setFullName(row.name());
            if (row.phone() != null && !row.phone().isBlank()) {
                candidate.setPhone(row.phone());
            }
            candidate.setImportBatchId(batch.getId());
            // Résumés cannot come through a CSV (§7.3.1), so imported candidates
            // start without one. Question generation falls back to JD-only and
            // records resume_missing on the session.
            candidate.setResumeExtractionStatus(PipelineStatus.PENDING);
            created.add(candidateRepository.save(candidate));
        }

        batch.setValidCount(created.size());
        batch.setStatus(ImportBatchStatus.COMPLETED);
        batchRepository.save(batch);

        log.info("Import completed: batchId={} imported={} reservedPaise={}",
                batch.getId(), created.size(), required);

        return new ImportResult(batch.getId(), created.size(), required,
                created.stream().map(Candidate::getId).toList());
    }

    /**
     * Abandons a previewed import.
     *
     * <p>Nothing to release: a batch in PREVIEW never took a reservation, which
     * is the whole reason preview and confirm are separate steps.
     */
    @Transactional
    public void cancelPreview(UUID batchId) {
        CandidateImportBatch batch = requireBatch(batchId);
        if (batch.getStatus() == ImportBatchStatus.PREVIEW) {
            batch.setStatus(ImportBatchStatus.REJECTED);
            batchRepository.save(batch);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private JobOpening requireJob(UUID jobOpeningId) {
        UUID companyId = SecurityContext.requireCompanyId();
        return jobRepository.findByCompanyIdAndId(companyId, jobOpeningId)
                .orElseThrow(() -> new ResourceNotFoundException("JobOpening", jobOpeningId));
    }

    private CandidateImportBatch requireBatch(UUID batchId) {
        UUID companyId = SecurityContext.requireCompanyId();
        return batchRepository.findByCompanyIdAndId(companyId, batchId)
                .orElseThrow(() -> new ResourceNotFoundException("CandidateImportBatch", batchId));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise import metadata: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Result types
    // =========================================================================

    /**
     * @param summary the preview line §7.3.1 specifies, e.g.
     *                "47 valid, 3 duplicates, 2 invalid"
     * @param reservationRequiredPaise what the whole batch will reserve, so the
     *                                 UI can show it before the recruiter commits
     */
    public record ImportPreview(UUID batchId,
                                int rowCount,
                                int validCount,
                                int duplicateCount,
                                int invalidCount,
                                long reservationRequiredPaise,
                                String summary,
                                List<RowOutcome> rows) {}

    public record ImportResult(UUID batchId,
                               int importedCount,
                               long reservedPaise,
                               List<UUID> candidateIds) {}

    /** One row's fate, with the line number so the recruiter can find it. */
    public record RowOutcome(int lineNumber, String name, String email,
                             String outcome, List<String> problems) {

        static RowOutcome valid(ParsedRow row) {
            return new RowOutcome(row.lineNumber(), row.name(), row.email(), "VALID", List.of());
        }

        static RowOutcome duplicate(ParsedRow row) {
            return new RowOutcome(row.lineNumber(), row.name(), row.email(), "DUPLICATE",
                    List.of("this candidate is already on the opening"));
        }

        static RowOutcome invalid(ParsedRow row, List<String> problems) {
            return new RowOutcome(row.lineNumber(), row.name(), row.email(), "INVALID", problems);
        }
    }
}
