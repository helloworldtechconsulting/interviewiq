package com.interviewiq.candidate.service;

import com.interviewiq.candidate.domain.Candidate;
import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.shared.config.WorkerProperties;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.storage.service.StorageService;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
 * Scheduled worker that extracts plain text from uploaded candidate resume files.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Poll {@code candidates} for rows with
 *       {@code resume_extraction_status IN (PENDING, IN_PROGRESS)} and a non-null key.
 *   <li>IN_PROGRESS items are included for crash recovery; safe because {@code fixedDelay}
 *       prevents concurrent scheduler runs.
 *   <li>Mark each row {@code IN_PROGRESS}, download bytes, parse with Tika,
 *       persist text, mark {@code DONE} or {@code FAILED}.
 * </ol>
 *
 * <h2>AI pipeline impact</h2>
 * <p>Resume text is the second input to the question generation prompt.
 * If extraction is FAILED or still PENDING when {@link QuestionGenerationWorker} runs,
 * the worker generates questions from JD text only — resume personalisation is
 * best-effort, not a hard dependency.
 *
 * <h2>Self-injection for @Transactional</h2>
 * <p>The {@code self} field holds a {@code @Lazy} proxy reference to this bean so that
 * {@code extractSingle} is called through the Spring AOP proxy and {@code @Transactional}
 * takes effect. Direct {@code this.extractSingle()} calls bypass the proxy entirely.
 *
 * <h2>Stub mode</h2>
 * <p>When {@link StorageService#downloadObject} returns empty bytes, a placeholder
 * string is stored. This allows full local smoke testing without real S3.
 */
@Component
public class ResumeExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionWorker.class);

    private static final int TIKA_MAX_CHARS = -1;

    private final CandidateRepository candidateRepository;
    private final StorageService      storageService;
    private final WorkerProperties    workerProperties;

    /**
     * Self-reference injected lazily to route {@link #extractSingle} calls through the proxy.
     */
    @Lazy
    @Autowired
    private ResumeExtractionWorker self;

    public ResumeExtractionWorker(CandidateRepository candidateRepository,
                                  StorageService storageService,
                                  WorkerProperties workerProperties) {
        this.candidateRepository = candidateRepository;
        this.storageService      = storageService;
        this.workerProperties    = workerProperties;
    }

    /**
     * Main extraction loop — runs every 30 seconds with a 15-second initial delay
     * (staggered from JdExtractionWorker's 10-second delay to spread DB load).
     */
    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT30S")
    public void extractPendingResumes() {
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minus(workerProperties.getStaleClaimAfter());

        // Claims a distinct, bounded batch per pod (PRD v2.1 §7.9).
        List<Candidate> claimed = candidateRepository.claimForResumeExtraction(
                workerProperties.getExtractionBatchSize(), staleBefore);

        if (claimed.isEmpty()) return;

        log.debug("ResumeExtractionWorker: claimed {} resume(s)", claimed.size());

        for (Candidate candidate : claimed) {
            self.extractSingle(candidate);  // call through proxy so @Transactional applies
        }
    }

    /**
     * Extracts text from a single resume file within its own transaction.
     *
     * <p><strong>Must be called via {@code self.extractSingle()} from the scheduler.</strong>
     */
    @Transactional
    public void extractSingle(Candidate candidate) {
        candidate.setResumeExtractionStatus(PipelineStatus.IN_PROGRESS);
        candidateRepository.save(candidate);

        try {
            byte[] fileBytes = storageService.downloadObject(candidate.getResumeS3Key());
            String extractedText = extractText(fileBytes, candidate.getId().toString());

            candidate.setResumeText(extractedText);
            candidate.setResumeExtractionStatus(PipelineStatus.DONE);
            candidateRepository.save(candidate);

            log.info("ResumeExtractionWorker: extracted {} chars from resume candidateId={}",
                    extractedText.length(), candidate.getId());

        } catch (Exception e) {
            log.error("ResumeExtractionWorker: failed to extract resume candidateId={}",
                    candidate.getId(), e);
            candidate.setResumeExtractionStatus(PipelineStatus.FAILED);
            candidateRepository.save(candidate);
        }
    }

    private String extractText(byte[] fileBytes, String logContext)
            throws TikaException, SAXException, IOException {
        if (fileBytes.length == 0) {
            log.info("ResumeExtractionWorker: [STUB] placeholder resume text for context={}", logContext);
            return "[STUB] Candidate resume text — replace with real content in non-stub environments.";
        }

        BodyContentHandler handler = new BodyContentHandler(TIKA_MAX_CHARS);
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();

        try (ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes)) {
            parser.parse(stream, handler, metadata);
        }

        String text = handler.toString().trim();
        return text.isEmpty() ? "[EMPTY] No text content extracted from resume." : text;
    }
}
