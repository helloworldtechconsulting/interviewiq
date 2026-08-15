package com.interviewiq.shared.metrics;

import com.interviewiq.session.infrastructure.EvaluationReportRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Application metrics for the evaluation pipeline (PRD v2.1 §8).
 *
 * <h2>Why these two and not more</h2>
 *
 * <p>Micrometer already exports JVM, HTTP and HikariCP metrics for free, and
 * those cover most of what goes wrong with a pod. What they cannot see is the
 * one number the product actually promises: §8 commits to a report within 30
 * minutes, and nothing in the default metric set says whether that is holding.
 *
 * <p>Depth and age are both here because either alone misleads. A hundred
 * reports queued a minute ago is healthy; a single report stuck for forty
 * minutes is a breach. Alerting on depth would page for the first and stay
 * silent for the second.
 *
 * <h2>Why a cached value rather than a query per scrape</h2>
 *
 * <p>Gauges are evaluated when Prometheus scrapes, and Prometheus scrapes every
 * pod. A gauge that runs a {@code COUNT(*)} inline would put one query per pod
 * per scrape interval against the database forever — a standing load added for
 * the sake of observing load. The value is refreshed on a timer instead, and
 * the gauge reads the cached number.
 */
@Component
public class PipelineMetrics {

    private final EvaluationReportRepository reportRepository;

    private final AtomicLong pendingReports   = new AtomicLong(0);
    private final AtomicLong oldestPendingAge = new AtomicLong(0);

    public PipelineMetrics(MeterRegistry registry, EvaluationReportRepository reportRepository) {
        this.reportRepository = reportRepository;

        Gauge.builder("interviewiq.evaluation.queue.depth", pendingReports, AtomicLong::get)
                .description("Evaluation reports awaiting generation")
                .register(registry);

        Gauge.builder("interviewiq.evaluation.queue.oldest.age.seconds", oldestPendingAge, AtomicLong::get)
                .description("Age of the oldest evaluation report still pending; 0 when the queue is empty")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * Refreshes the cached values.
     *
     * <p>Scheduled rather than event-driven, and read-only. Note this runs on
     * <em>every</em> pod including web pods, where schedulers are otherwise off
     * — deliberately, because the metric has to be scrapeable wherever
     * Prometheus finds a pod. The cost is one cheap indexed query per pod per
     * minute.
     */
    @Scheduled(fixedDelayString = "PT1M")
    @Transactional(readOnly = true)
    public void refresh() {
        pendingReports.set(reportRepository.countPendingWork());

        Double age = reportRepository.oldestPendingAgeSeconds();
        // Null when nothing is pending. Reported as 0 rather than left at its
        // previous value, which would otherwise keep an alert firing after the
        // backlog cleared.
        oldestPendingAge.set(age == null ? 0L : age.longValue());
    }

    /** Populate immediately at startup so the first scrape is not a lie. */
    @EventListener(ApplicationReadyEvent.class)
    public void primeOnStartup() {
        refresh();
    }
}
