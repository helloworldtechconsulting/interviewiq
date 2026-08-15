package com.interviewiq.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the polling workers (PRD v2.1 §7.9).
 *
 * <p>Bound to the {@code app.worker} namespace.
 *
 * <h2>On batch size</h2>
 *
 * <p>§7.9 requires an <em>explicit</em> batch limit on every claim query, not
 * merely a claim query. An unbounded fetch lets a single pod claim an entire
 * backlog, which defeats the point of spreading work across pods and makes one
 * pod's death far more expensive.
 *
 * <p>The defaults are sized against the SLA rather than guessed. At a 30-minute
 * hard evaluation SLA, "even a single pod processing serially clears a
 * 50-interview burst inside the window (50 × ~20s ≈ 17 minutes)". A batch of 10
 * per 30-second poll clears that burst comfortably while keeping any one pod's
 * claim small enough that losing it costs little.
 */
@ConfigurationProperties(prefix = "app.worker")
public class WorkerProperties {

    /**
     * How long a claim may go unfinished before another pod may take the work.
     *
     * <p>Must exceed the longest legitimate processing time, or a healthy pod's
     * in-flight work gets stolen and processed twice. Question generation and
     * evaluation are each ~20 seconds typical; five minutes leaves generous room
     * for a slow LLM response without leaving genuinely dead work stranded.
     */
    private Duration staleClaimAfter = Duration.ofMinutes(5);

    private int evaluationBatchSize = 10;

    private int questionGenerationBatchSize = 10;

    private int extractionBatchSize = 20;

    private int expiryBatchSize = 100;

    private int promoExpiryBatchSize = 100;

    /** Payload-stripping sweep over the domain event log (INTIQ-98). */
    private int tracePayloadSweepBatchSize = 500;

    /**
     * Concurrent LLM calls per pod during evaluation.
     *
     * <p>The claimed batch is processed in parallel on virtual threads, bounded
     * by a semaphore of about 4 to stay inside provider rate limits (§7.5.5).
     * This is what delivers the ~5-minute soft target — it is an optimisation
     * against the soft target, not a requirement of the 30-minute hard SLA,
     * which serial processing already meets.
     */
    private int evaluationConcurrency = 4;

    public Duration getStaleClaimAfter() { return staleClaimAfter; }
    public void setStaleClaimAfter(Duration staleClaimAfter) { this.staleClaimAfter = staleClaimAfter; }

    public int getEvaluationBatchSize() { return evaluationBatchSize; }
    public void setEvaluationBatchSize(int evaluationBatchSize) { this.evaluationBatchSize = evaluationBatchSize; }

    public int getQuestionGenerationBatchSize() { return questionGenerationBatchSize; }
    public void setQuestionGenerationBatchSize(int v) { this.questionGenerationBatchSize = v; }

    public int getExtractionBatchSize() { return extractionBatchSize; }
    public void setExtractionBatchSize(int extractionBatchSize) { this.extractionBatchSize = extractionBatchSize; }

    public int getExpiryBatchSize() { return expiryBatchSize; }
    public void setExpiryBatchSize(int expiryBatchSize) { this.expiryBatchSize = expiryBatchSize; }

    public int getPromoExpiryBatchSize() { return promoExpiryBatchSize; }
    public void setPromoExpiryBatchSize(int promoExpiryBatchSize) { this.promoExpiryBatchSize = promoExpiryBatchSize; }

    public int getTracePayloadSweepBatchSize() { return tracePayloadSweepBatchSize; }
    public void setTracePayloadSweepBatchSize(int v) { this.tracePayloadSweepBatchSize = v; }

    public int getEvaluationConcurrency() { return evaluationConcurrency; }
    public void setEvaluationConcurrency(int evaluationConcurrency) { this.evaluationConcurrency = evaluationConcurrency; }
}
