package com.interviewiq.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-workflow AI vendor and model selection (PRD v2.1 §9.1).
 *
 * <p>Bound to {@code app.ai}. Vendor <em>and</em> model are configurable per
 * workflow, and each workflow gets its own {@code ChatClient} bean built from
 * this and injected by qualifier:
 *
 * <pre>
 * app:
 *   ai:
 *     question:   { vendor: openai,    model: gpt-5.4-nano }
 *     followup:   { vendor: openai,    model: gpt-5.4-nano }
 *     evaluation: { vendor: anthropic, model: claude-haiku-4-5 }
 * </pre>
 *
 * <p>The PRD gives three reasons this is configuration rather than code:
 * empirical vendor selection rather than a guess; failover, since a provider
 * outage becomes a config flip and a redeploy; and a negotiating position once
 * spend becomes meaningful.
 *
 * <h2>Why evaluation is deliberately undecided</h2>
 *
 * <p>§13.1 is unusually candid: "Accuracy is not knowable from public data." The
 * benchmark that matters — correlation between model score and actual hiring
 * outcome, on Indian SMB screening interviews in Indian English — does not exist.
 * So the plan is to instrument rather than guess: score the first ~50 interviews
 * with both candidates, serve one and log both, and select on real Pearson r for
 * about ₹900 total. At the mini/Haiku tier the two vendors are within 16% of each
 * other, roughly ₹125/month across 500 interviews — "that is noise; do not choose
 * a vendor on it."
 *
 * <p>Model version strings must be confirmed against the provider price list at
 * implementation time. The price <em>tiers</em> are stable; the labels are not.
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiWorkflowProperties {

    /** Question generation — templated, structured, low-judgement. Cheapest tier. */
    private final Workflow question = new Workflow("openai", "gpt-5.4-nano");

    /** Follow-up decision — trivial real-time classification, latency-sensitive. */
    private final Workflow followup = new Workflow("openai", "gpt-5.4-nano");

    /**
     * Evaluation — score quality is the product.
     *
     * <p>Defaults to Haiku because, forced to choose blind, §13.1 picks it: "because
     * calibration matters more than ₹0.25". LLM-as-judge systems typically fail by
     * clustering everyone in the 70–85 band, which destroys the ranking signal the
     * product is sold on.
     */
    private final Workflow evaluation = new Workflow("anthropic", "claude-haiku-4-5");

    /**
     * Shadow mode: score with both vendors, serve one, log both (§13.1).
     *
     * <p>Off by default; enabled for the first ~50 interviews to produce a real
     * per-vendor correlation against "did you hire this candidate?" feedback.
     */
    private boolean shadowEvaluation = false;

    private String shadowVendor = "openai";

    private String shadowModel = "gpt-5.4-mini";

    /** Maximum retries before a report is flagged ERROR for manual review (§7.5.5). */
    private int evaluationMaxAttempts = 3;

    /**
     * Prompt caching on the JD and system prompt (§7.5.1, §13.1).
     *
     * <p>One opening generates questions for up to 200 candidates against the
     * same JD. Cache reads cost about 10% of base input, cutting
     * question-generation input cost by roughly 90% — "this saves more than
     * switching vendors does."
     */
    private boolean promptCaching = true;

    public Workflow getQuestion()   { return question; }
    public Workflow getFollowup()   { return followup; }
    public Workflow getEvaluation() { return evaluation; }

    public boolean isShadowEvaluation() { return shadowEvaluation; }
    public void setShadowEvaluation(boolean shadowEvaluation) { this.shadowEvaluation = shadowEvaluation; }

    public String getShadowVendor() { return shadowVendor; }
    public void setShadowVendor(String shadowVendor) { this.shadowVendor = shadowVendor; }

    public String getShadowModel() { return shadowModel; }
    public void setShadowModel(String shadowModel) { this.shadowModel = shadowModel; }

    public int getEvaluationMaxAttempts() { return evaluationMaxAttempts; }
    public void setEvaluationMaxAttempts(int v) { this.evaluationMaxAttempts = v; }

    public boolean isPromptCaching() { return promptCaching; }
    public void setPromptCaching(boolean promptCaching) { this.promptCaching = promptCaching; }

    /** One workflow's vendor, model and sampling temperature. */
    public static class Workflow {

        private String vendor;
        private String model;

        /**
         * Low by default. Every workflow here is a structured, rubric-following
         * task rather than a creative one, and evaluation in particular needs to
         * be reproducible enough that the same transcript scores the same twice.
         */
        private double temperature = 0.2;

        public Workflow() {}

        Workflow(String vendor, String model) {
            this.vendor = vendor;
            this.model = model;
        }

        public String getVendor() { return vendor; }
        public void setVendor(String vendor) { this.vendor = vendor; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        @Override
        public String toString() {
            return vendor + "/" + model;
        }
    }
}
