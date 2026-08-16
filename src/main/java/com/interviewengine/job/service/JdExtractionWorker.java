package com.interviewengine.job.service;

import com.interviewengine.job.domain.JobOpening;
import com.interviewengine.job.infrastructure.JobOpeningRepository;
import com.interviewengine.shared.config.WorkerProperties;
import com.interviewengine.shared.domain.PipelineStatus;
import com.interviewengine.storage.service.StorageService;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Scheduled worker that extracts plain text from uploaded JD files (PDF/DOCX).
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Poll {@code job_openings} for rows with {@code jd_extraction_status IN (PENDING, IN_PROGRESS)}
 *       and a non-null {@code jd_s3_key}.
 *   <li>IN_PROGRESS items are included for crash recovery — {@code fixedDelay} guarantees only one
 *       scheduler invocation runs at a time, so an IN_PROGRESS item from a previous crashed run
 *       is always safe to re-process.
 *   <li>Mark each row {@code IN_PROGRESS}, download bytes, run Tika, persist {@code DONE} or {@code FAILED}.
 * </ol>
 *
 * <h2>Self-injection for @Transactional</h2>
 * <p>{@code extractSingle} must be called through the Spring AOP proxy — not via {@code this} — for
 * {@code @Transactional} to apply. The {@code self} field holds a {@code @Lazy} reference to the
 * proxy of this same bean, injected after the application context is fully initialised.
 *
 * <h2>Stub mode</h2>
 * <p>When {@link StorageService#downloadObject} returns an empty byte array (local AWS stub),
 * a placeholder text is stored. This allows full local smoke testing without real S3.
 *
 * <h2>Session creation gate</h2>
 * <p>{@code SessionService.create()} blocks until {@code jdExtractionStatus == DONE}.
 * This worker's DONE transition is what unlocks session creation for a job.
 */
@Component
@ConditionalOnProperty(name = "app.schedulers.enabled", havingValue = "true", matchIfMissing = true)
public class JdExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(JdExtractionWorker.class);

    /** Max characters Tika will extract per document (-1 = unlimited). */
    private static final int TIKA_MAX_CHARS = -1;

    private final JobOpeningRepository jobOpeningRepository;
    private final StorageService       storageService;
    private final WorkerProperties     workerProperties;

    /**
     * Self-reference injected lazily to ensure calls to {@link #extractSingle} go through
     * the Spring AOP proxy, activating the {@code @Transactional} advice.
     */
    @Lazy
    @Autowired
    private JdExtractionWorker self;

    public JdExtractionWorker(JobOpeningRepository jobOpeningRepository,
                               StorageService storageService,
                               WorkerProperties workerProperties) {
        this.jobOpeningRepository = jobOpeningRepository;
        this.storageService       = storageService;
        this.workerProperties     = workerProperties;
    }

    /**
     * Main extraction loop — runs every 30 seconds with an initial 10-second delay.
     *
     * <p>Fixed-delay (not fixed-rate) guarantees only one run at a time even if
     * processing takes longer than the interval. Uses virtual threads (Spring Boot 3.2+
     * with {@code spring.threads.virtual.enabled=true}) so blocking S3 calls are cheap.
     *
     * <p>IN_PROGRESS items (from a previous crashed JVM run) are included in the query
     * and will be re-processed. This is safe because fixedDelay prevents concurrent runs.
     */
    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT30S")
    public void extractPendingJds() {
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minus(workerProperties.getStaleClaimAfter());

        // Claims a distinct, bounded batch per pod (PRD v2.1 §7.9). The previous
        // derived query returned the same rows on every pod.
        List<JobOpening> claimed = jobOpeningRepository.claimForJdExtraction(
                workerProperties.getExtractionBatchSize(), staleBefore);

        if (claimed.isEmpty()) return;

        log.debug("JdExtractionWorker: claimed {} JD(s)", claimed.size());

        for (JobOpening job : claimed) {
            self.extractSingle(job);  // call through proxy so @Transactional applies
        }
    }

    /**
     * Extracts text from a single JD file within its own transaction.
     *
     * <p><strong>Called via {@code self.extractSingle()} from the scheduler to ensure
     * the Spring proxy is used and {@code @Transactional} takes effect.</strong>
     * Each item runs in an isolated transaction so one failure does not affect others.
     */
    @Transactional
    public void extractSingle(JobOpening job) {
        // Mark IN_PROGRESS — prevents another instance from picking up the same item
        job.setJdExtractionStatus(PipelineStatus.IN_PROGRESS);
        jobOpeningRepository.save(job);

        try {
            byte[] fileBytes = storageService.downloadObject(job.getJdS3Key());
            String extractedText = extractText(fileBytes, job.getId().toString());

            job.setJdText(extractedText);
            job.setJdExtractionStatus(PipelineStatus.DONE);
            jobOpeningRepository.save(job);

            log.info("JdExtractionWorker: extracted {} chars from JD jobId={}",
                    extractedText.length(), job.getId());

        } catch (Exception e) {
            log.error("JdExtractionWorker: failed to extract JD jobId={}", job.getId(), e);
            job.setJdExtractionStatus(PipelineStatus.FAILED);
            jobOpeningRepository.save(job);
        }
    }

    /**
     * Extracts plain text from raw file bytes using Apache Tika's auto-detect parser.
     *
     * <p>If the byte array is empty (stub mode), returns a deterministic placeholder
     * that downstream AI workers can recognise and skip.
     */
    private String extractText(byte[] fileBytes, String logContext)
            throws TikaException, SAXException, IOException {
        if (fileBytes.length == 0) {
            log.info("JdExtractionWorker: [STUB] placeholder JD text for context={}", logContext);
            return "[STUB] Job description text — replace with real content from S3 in non-stub environments.";
        }

        BodyContentHandler handler = new BodyContentHandler(TIKA_MAX_CHARS);
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();

        try (ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes)) {
            parser.parse(stream, handler, metadata);
        }

        String text = handler.toString().trim();
        return text.isEmpty() ? "[EMPTY] No text content extracted from document." : text;
    }
}
